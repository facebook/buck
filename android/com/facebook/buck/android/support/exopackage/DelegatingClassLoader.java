/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Copyright 2017present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.android.support.exopackage;

import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link ClassLoader} similar to {@link dalvik.system.DexClassLoader}. This loader aims to allow
 * new definitions of a class to be "hotswapped" into the app without incurring a restart. The
 * typical use case is an iterative development cycle.
 *
 * <p>Background: Classloading in Java: Each class definition in Java is loaded and cached by a
 * ClassLoader. Once loaded, a class definition remains in the ClassLoader's cache permanently, and
 * subsequent lookups for the class will receive the cached version. ClassLoaders can be chained
 * together, so that if a given loader does not have a definition for a class, it can pass the
 * request on to its parent. Each class maintains a reference to its ClassLoader and uses that
 * reference to resolve new class lookups.
 *
 * <p>Implementation details: To enable hotswapping code, we need to unload the old versions of a
 * class and load a new one. The above caching behavior means that we cannot unload a class without
 * unloading its ClassLoader. DelegatingClassLoader achieves this by creating a delegate
 * DexClassLoader instance which loads the given DexFiles. To unload a DexFile, the entire delegate
 * DexClassLoader is dropped and a new one is built.
 */
class DelegatingClassLoader extends ClassLoader {

  private static final String TAG = "DelegatingCL";
  private File mDexOptDir;

  private PathClassLoader mDelegate;
  private Set<String> mManagedClasses = new HashSet<>();

  private static DelegatingClassLoader sInstalledClassLoader;

  private static final Method sFIND_CLASS;

  static {
    try {
      sFIND_CLASS = BaseDexClassLoader.class.getDeclaredMethod("findClass", String.class);
      sFIND_CLASS.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  /** @return (and potentially create) an instance of DelegatingClassLoader */
  static DelegatingClassLoader getInstance() {
    if (sInstalledClassLoader == null) {
      Log.d(TAG, "Installing DelegatingClassLoader");
      final ClassLoader parent = DelegatingClassLoader.class.getClassLoader();
      sInstalledClassLoader = new DelegatingClassLoader(parent);
      sInstalledClassLoader.installHelperAboveApplicationClassLoader();
    }
    return sInstalledClassLoader;
  }

  private DelegatingClassLoader(ClassLoader parent) {
    super(parent);
  }

  /**
   * Install the helper classloader above the application's ClassLoader, and below the system CL.
   * See {@link AboveAppClassLoader} below for a detailed explanation of its purpose.
   */
  private void installHelperAboveApplicationClassLoader() {
    try {
      ClassLoader appClassLoader = getClass().getClassLoader();
      Field parentField = ClassLoader.class.getDeclaredField("parent");
      parentField.setAccessible(true);
      ClassLoader systemClassLoader = (ClassLoader) parentField.get(appClassLoader);
      final ClassLoader instance = new AboveAppClassLoader(systemClassLoader);
      parentField.set(appClassLoader, instance);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * If the host application's default ClassLoader needs to load a class from our delegate, we need
   * to intercept that class request. We cannot/do not want to install DelegatingClassLoader above
   * the AppCL because we need to allow managed classes to request classes from the primary dex,
   * thus we need to query the AppCL from the DCL. Placing the DCL above the AppCL would create a
   * loop, so we install this AboveAppClassLoader to catch any requests for managed classes (e.g.
   * resolution of an Activity from an Intent) which the AppCL receives.
   */
  private class AboveAppClassLoader extends ClassLoader {
    private AboveAppClassLoader(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      final DelegatingClassLoader instance = DelegatingClassLoader.getInstance();
      if (mManagedClasses.contains(name)) {
        return instance.loadManagedClass(name);
      } else {
        throw new ClassNotFoundException(name);
      }
    }
  }

  /**
   * We need to override loadClass rather than findClass because the default impl of loadClass will
   * first check the loaded class cache (and populate the cache from the result of findClass). We
   * allow our delegate to maintain its own loaded class cache and ours will remain empty.
   */
  @Override
  public Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
    Class<?> clazz;
    if (mManagedClasses.contains(className)) {
      clazz = loadManagedClass(className);
    } else {
      clazz = getParent().loadClass(className);
    }
    if (resolve) {
      resolveClass(clazz);
    }
    return clazz;
  }

  /**
   * Try to load the class definition from the DexFiles that this loader manages. Does not delegate
   * to parent. Users of DelegatingClassLoader should prefer calling this whenever the class is
   * known to exist in a hotswappable module.
   */
  private Class<?> loadManagedClass(String className) throws ClassNotFoundException {
    if (mDelegate == null) {
      throw new RuntimeException(
          "DelegatingCL was not initialized via ExopackageDexLoader.loadExopackageJars");
    }
    try {
      return (Class) sFIND_CLASS.invoke(mDelegate, className);
    } catch (Exception e) {
      throw new ClassNotFoundException("Unable to find class " + className, e.getCause());
    }
  }

  @Override
  public String toString() {
    return "DelegatingClassLoader";
  }

  /**
   * Clear the existing delegate and return the new one, populated with the given dex files
   *
   * @param dexJars the .dex.jar files which will be managed by our delegate
   */
  void resetDelegate(List<File> dexJars) {
    mDelegate = new PathClassLoader("", "", this);
    mManagedClasses.clear();
    SystemClassLoaderAdder.installDexJars(mDelegate, mDexOptDir, dexJars);
    for (File dexJar : dexJars) {
      try {
        DexFile dexFile = new DexFile(dexJar);
        final Enumeration<String> entries = dexFile.entries();
        while (entries.hasMoreElements()) {
          mManagedClasses.add(entries.nextElement());
        }
      } catch (IOException e) {
        // Pass for now
      }
    }
  }

  /**
   * Provide an output dir where optimized dex file outputs can live. The file is assumed to already
   * exist and to be a directory
   *
   * @param dexOptDir output directory for the dex-optimization process
   * @return the instance of DCL for chaining convenience
   */
  DelegatingClassLoader setDexOptDir(File dexOptDir) {
    mDexOptDir = dexOptDir;
    return this;
  }
}
