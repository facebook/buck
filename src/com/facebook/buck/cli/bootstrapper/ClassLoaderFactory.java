/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.cli.bootstrapper;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.stream.Stream;

/** Creates a ClassLoader from {@code System.getenv()}. classpath entries. */
public class ClassLoaderFactory {

  /** Expose a provider to facilitate mock tests. */
  @FunctionalInterface
  public interface ClassPathProvider extends Function<String, String> {}

  static final String BUCK_CLASSPATH = "BUCK_CLASSPATH";
  static final String EXTRA_BUCK_CLASSPATH = "EXTRA_BUCK_CLASSPATH";

  private final ClassPathProvider classPathProvider;

  @SuppressWarnings("PMD.BlacklistedSystemGetenv")
  static ClassLoaderFactory withEnv() {
    return new ClassLoaderFactory(System.getenv()::get);
  }

  ClassLoaderFactory(ClassPathProvider classPathProvider) {
    this.classPathProvider = requireNonNull(classPathProvider);
  }

  /**
   * Create a new ClassLoader that concats {@value BUCK_CLASSPATH} and {@value EXTRA_BUCK_CLASSPATH}
   *
   * @return ClassLoader instance to env classpath.
   */
  public ClassLoader create() {
    // BUCK_CLASSPATH is not set by a user, no need to use EnvVariablesProvider.
    String classPath = classPathProvider.apply(BUCK_CLASSPATH);
    String extraClassPath = classPathProvider.apply(EXTRA_BUCK_CLASSPATH);

    if (classPath == null) {
      throw new RuntimeException(BUCK_CLASSPATH + " not set");
    }

    URL[] urls =
        Stream.of(classPath, extraClassPath)
            .flatMap(this::splitPaths)
            .filter(this::nonBlank)
            .map(this::toUrl)
            .toArray(URL[]::new);

    return new URLClassLoader(urls);
  }

  private Stream<String> splitPaths(String paths) {
    if (paths == null) {
      return Stream.empty();
    }
    return Stream.of(paths.split(File.pathSeparator));
  }

  private boolean nonBlank(String path) {
    return !path.isBlank();
  }

  private URL toUrl(String path) {
    try {
      return Paths.get(path).toUri().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}
