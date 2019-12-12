/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ClassLoaderCacheTest {
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();
  private File fooDir;
  private File barDir;
  private File bazFile;

  @Before
  public void setupTempFolder() throws IOException {
    fooDir = tempFolder.newFolder("foo");
    barDir = tempFolder.newFolder("bar");
    bazFile = new File(barDir, "baz");
    bazFile.createNewFile();
  }

  @Test
  public void cacheLoaderReturnsSameClassLoader() throws Exception {
    try (ClassLoaderCache clc = new ClassLoaderCache()) {
      ClassLoader dummyParent = ClassLoader.getSystemClassLoader();
      ImmutableList<URL> dummyClassPath =
          ImmutableList.of(fooDir.toURI().toURL(), barDir.toURI().toURL());
      ClassLoader cl1 = clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);
      ClassLoader cl2 = clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);

      // The class loader had better have been cached
      assertSame(cl1, cl2);

      // And the class loader should contain the URLs we supplied
      URL[] dummyUrls = FluentIterable.from(dummyClassPath).toArray(URL.class);

      assertArrayEquals(dummyUrls, ((URLClassLoader) cl1).getURLs());
    }
  }

  @Test
  public void cacheLoaderClosesClassLoaders() throws Exception {
    ClassLoader cl;
    try (ClassLoaderCache clc = new ClassLoaderCache()) {
      ClassLoader dummyParent = ClassLoader.getSystemClassLoader();
      ImmutableList<URL> dummyClassPath =
          ImmutableList.of(fooDir.toURI().toURL(), barDir.toURI().toURL());
      cl = clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);

      assumeThat(cl.getResource("baz"), Matchers.equalTo(bazFile.toURI().toURL()));
    }

    // When the class loader is closed, resources are no longer accessible
    assertNull(cl.getResource("baz"));
  }

  @Test
  public void callersCannotCloseCachedClassLoaders() throws Exception {
    URLClassLoader cl;
    try (ClassLoaderCache clc = new ClassLoaderCache()) {
      ClassLoader dummyParent = ClassLoader.getSystemClassLoader();
      ImmutableList<URL> dummyClassPath =
          ImmutableList.of(fooDir.toURI().toURL(), barDir.toURI().toURL());
      cl = (URLClassLoader) clc.getClassLoaderForClassPath(dummyParent, dummyClassPath);

      assumeThat(cl.getResource("baz"), Matchers.equalTo(bazFile.toURI().toURL()));
      cl.close();

      // Because the class loader isn't closed, we can still get at the resource
      assertThat(cl.getResource("baz"), Matchers.equalTo(bazFile.toURI().toURL()));
    }
  }
}
