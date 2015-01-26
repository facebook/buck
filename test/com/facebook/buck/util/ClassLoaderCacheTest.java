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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.FluentIterable;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

public class ClassLoaderCacheTest {
  private static final String DUMMYDIR = "d7b9d9fd-1a83-4c76-8981-52deb0fa4d17";

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

  @Test
  public void cacheLoaderReturnsSameClassLoader() throws Exception {
    try (ClassLoaderCache clc = new ClassLoaderCache()) {
    ClassLoader dummyParent = ClassLoader.getSystemClassLoader();
    ImmutableList<Path> dummyClassPath = ImmutableList.of(
        Paths.get(DUMMYDIR, "foo"),
        Paths.get(DUMMYDIR, "bar"));
    ClassLoader cl1 = clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);
    ClassLoader cl2 = clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);

    // The class loader had better have been cached
    assertSame(cl1, cl2);

    // And the class loader should contain the URLs we supplied
    URL[] dummyUrls = FluentIterable.from(dummyClassPath)
        .transform(PATH_TO_URL)
        .toArray(URL.class);

    assertArrayEquals(
        dummyUrls,
        ((URLClassLoader) cl1).getURLs());
    }
  }
}
