/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Maintain a cache mapping class paths to class loaders that load from these class paths. The class
 * loaders remain active until ClassLoaderCache itself is unloaded.
 */
public final class ClassLoaderCache implements AutoCloseable {

  private final Map<ClassLoader, Map<ImmutableList<URL>, ClassLoader>> cache = new HashMap<>();

  private int referenceCount = 1;

  private synchronized Map<ImmutableList<URL>, ClassLoader> getCacheForParent(
      @Nullable ClassLoader parentClassLoader) {
    Map<ImmutableList<URL>, ClassLoader> cacheForParent = cache.get(parentClassLoader);

    if (cacheForParent == null) {
      cacheForParent = new HashMap<>();
      cache.put(parentClassLoader, cacheForParent);
    }

    return cacheForParent;
  }

  public synchronized ClassLoader getClassLoaderForClassPath(
      @Nullable ClassLoader parentClassLoader, ImmutableList<URL> classPath) {

    Map<ImmutableList<URL>, ClassLoader> cacheForParent = getCacheForParent(parentClassLoader);

    ClassLoader classLoader = cacheForParent.get(classPath);
    if (classLoader == null) {
      URL[] urls = classPath.toArray(new URL[0]);
      classLoader = new CachedURLClassLoader(urls, parentClassLoader);
      cacheForParent.put(classPath, classLoader);
    }

    return classLoader;
  }

  @VisibleForTesting
  public synchronized void injectClassLoader(
      @Nullable ClassLoader parentClassLoader,
      ImmutableList<URL> classPath,
      ClassLoader injectedClassLoader) {
    Map<ImmutableList<URL>, ClassLoader> cacheForParent = getCacheForParent(parentClassLoader);

    cacheForParent.put(classPath, injectedClassLoader);
  }

  public synchronized ClassLoaderCache addRef() {
    referenceCount += 1;
    return this;
  }

  @Override
  public synchronized void close() throws IOException {
    if (referenceCount > 1) {
      referenceCount -= 1;
      return;
    }

    Optional<IOException> caughtEx = Optional.empty();

    for (Map<ImmutableList<URL>, ClassLoader> cacheForParent : cache.values()) {
      for (ClassLoader cl : cacheForParent.values()) {
        try {
          if (cl instanceof CachedURLClassLoader) {
            ((CachedURLClassLoader) cl).reallyClose();
          }
        } catch (IOException ex) {
          if (caughtEx.isPresent()) {
            caughtEx.get().addSuppressed(ex);
          } else {
            caughtEx = Optional.of(ex);
          }
        }
      }
    }

    if (caughtEx.isPresent()) {
      throw caughtEx.get();
    }
  }

  private static class CachedURLClassLoader extends URLClassLoader {
    public CachedURLClassLoader(URL[] urls, @Nullable ClassLoader parent) {
      super(urls, parent);
    }

    @Override
    public void close() throws IOException {
      // Do nothing; only the cache can close this ClassLoader
    }

    private void reallyClose() throws IOException {
      super.close();
    }
  }
}
