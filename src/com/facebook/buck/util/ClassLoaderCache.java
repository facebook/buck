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

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

/**
 * Maintain a cache mapping class paths to class loaders that load from these class paths.  The
 * class loaders remain active until ClassLoaderCache itself is unloaded.
 */
public final class ClassLoaderCache implements AutoCloseable {

  private static final Function<Path, URL> PATH_TO_URL = new Function<Path, URL>() {
      @Override
      public URL apply(Path p) {
        try {
          return p.toUri().toURL();
        } catch (MalformedURLException e) {
          throw new RuntimeException(e);
        }
      }
    };

  private final Map<ClassLoader, Map<ImmutableList<Path>, ClassLoader>> cache = new HashMap<>();

  private int referenceCount = 1;

  private synchronized Map<ImmutableList<Path>, ClassLoader> getCacheForParent(
      @Nullable ClassLoader parentClassLoader) {
    Map<ImmutableList<Path>, ClassLoader> cacheForParent =
        cache.get(parentClassLoader);

    if (cacheForParent == null) {
      cacheForParent = new HashMap<>();
      cache.put(parentClassLoader, cacheForParent);
    }

    return cacheForParent;
  }

  public synchronized ClassLoader getClassLoaderForClassPath(
      @Nullable ClassLoader parentClassLoader,
      ImmutableList<Path> classPath) {

    Map<ImmutableList<Path>, ClassLoader> cacheForParent =
        getCacheForParent(parentClassLoader);

    ClassLoader classLoader = cacheForParent.get(classPath);
    if (classLoader == null) {
      URL[] urls = FluentIterable.from(classPath)
          .transform(PATH_TO_URL)
          .toArray(URL.class);
      classLoader = new URLClassLoader(urls, parentClassLoader);
      cacheForParent.put(classPath, classLoader);
    }

    return classLoader;
  }

  @VisibleForTesting
  public synchronized void injectClassLoader(
      @Nullable ClassLoader parentClassLoader,
      ImmutableList<Path> classPath,
      ClassLoader injectedClassLoader) {
    Map<ImmutableList<Path>, ClassLoader> cacheForParent =
        getCacheForParent(parentClassLoader);

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

    Optional<IOException> caughtEx = Optional.absent();

    for (Map<ImmutableList<Path>, ClassLoader> cacheForParent : cache.values()) {
      for (ClassLoader cl : cacheForParent.values()) {
        try {
          if (cl instanceof URLClassLoader) {
            ((URLClassLoader) cl).close();
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
}
