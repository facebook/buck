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

package com.facebook.buck.util.env;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.collect.ImmutableList;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.Test;

public class BuckClasspathTest {

  @Test(expected = ClassNotFoundException.class)
  public void testEmptyClassLoaderCantAccessBuckFiles() throws Exception {
    URLClassLoader classLoader = createClassLoader(ImmutableList.of());
    classLoader.loadClass(BuckClasspathTest.class.getName());
  }

  @Test
  public void testClasspathHasAccessToBuckFiles() throws Exception {
    URLClassLoader classLoader = createClassLoader(BuckClasspath.getClasspath());
    classLoader.loadClass(BuckClasspath.class.getName());
  }

  @Test
  public void testClasspathHasAccessToTestFiles() throws Exception {
    URLClassLoader classLoader = createClassLoader(BuckClasspath.getClasspath());
    classLoader.loadClass(BuckClasspathTest.class.getName());
  }

  @Test
  public void testBootstrapClasspathHasAccessToBootstrapper() throws Exception {
    URLClassLoader classLoader = createClassLoader(BuckClasspath.getBootstrapClasspath());
    classLoader.loadClass(ClassLoaderBootstrapper.class.getName());
  }

  // BUCK_CLASSPATH is internal variable, no need to use EnvVariablesProvider
  @SuppressWarnings("PMD.BlacklistedSystemGetenv")
  @Test(expected = ClassNotFoundException.class)
  public void testBootstrapClasspathDoesNotHaveAccesToBuckFiles() throws Exception {
    assumeTrue(System.getenv(BuckClasspath.ENV_VAR_NAME) != null);
    URLClassLoader classLoader = createClassLoader(BuckClasspath.getBootstrapClasspath());
    classLoader.loadClass(ImmutableList.class.getName());
  }

  public URLClassLoader createClassLoader(ImmutableList<Path> classpath) {
    URL[] urls =
        RichStream.from(classpath)
            .map(ThrowingFunction.asFunction(path -> path.toUri().toURL()))
            .toArray(URL[]::new);
    return new URLClassLoader(urls, null);
  }
}
