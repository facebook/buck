/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.javax;

import com.facebook.buck.util.JavaVersion;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * ToolProvider has no synchronization internally, so if we don't synchronize from the outside we
 * could wind up loading the compiler classes multiple times from different class loaders.
 */
public class SynchronizedToolProvider {

  private static Method getPlatformClassLoaderMethod;

  static {
    if (JavaVersion.getMajorVersion() >= 9) {
      try {
        getPlatformClassLoaderMethod = ClassLoader.class.getMethod("getPlatformClassLoader");
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static JavaCompiler getSystemJavaCompiler() {
    JavaCompiler compiler;
    synchronized (ToolProvider.class) {
      compiler = ToolProvider.getSystemJavaCompiler();
    }
    return compiler;
  }

  public static ClassLoader getSystemToolClassLoader() {
    if (JavaVersion.getMajorVersion() >= 9) {
      // The compiler classes are loaded using the platform class loader in Java 9+.
      try {
        return (ClassLoader) getPlatformClassLoaderMethod.invoke(null);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

    ClassLoader classLoader;
    synchronized (ToolProvider.class) {
      classLoader = ToolProvider.getSystemToolClassLoader();
    }
    return classLoader;
  }
}
