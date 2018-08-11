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

package com.facebook.buck.util.env;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Current process classpath. Set by buckd or by launcher script. */
public class BuckClasspath {

  public static final String ENV_VAR_NAME = "BUCK_CLASSPATH";
  public static final String BOOTSTRAP_ENV_VAR_NAME = "CLASSPATH";
  public static final String TEST_ENV_VAR_NAME = "BUCK_TEST_CLASSPATH_FILE";

  public static String getBuckClasspathFromEnvVarOrThrow() {
    String envVarValue = getBuckClasspathFromEnvVarOrNull();
    if (envVarValue == null) {
      throw new IllegalStateException(ENV_VAR_NAME + " env var is not set");
    }
    if (envVarValue.isEmpty()) {
      throw new IllegalStateException(ENV_VAR_NAME + " env var is set to empty string");
    }
    return envVarValue;
  }

  private static Optional<String> getBuckTestClasspath() {
    String envVarValue = System.getenv(TEST_ENV_VAR_NAME);
    return Optional.ofNullable(envVarValue);
  }

  @Nullable
  public static String getBuckClasspathFromEnvVarOrNull() {
    return System.getenv(ENV_VAR_NAME);
  }

  @Nullable
  public static String getBuckBootstrapClasspathFromEnvVarOrNull() {
    return System.getenv(BOOTSTRAP_ENV_VAR_NAME);
  }

  /** Returns Buck's "bootstrap" classpath. See ClassLoaderBootstrapper. */
  public static ImmutableList<Path> getBootstrapClasspath() throws IOException {
    String classpathFromEnv = getBuckBootstrapClasspathFromEnvVarOrNull();
    if (classpathFromEnv != null) {
      return Arrays.stream(classpathFromEnv.split(File.pathSeparator))
          .map(Paths::get)
          .collect(ImmutableList.toImmutableList());
    }
    return getBuckClasspathForIntellij();
  }

  /** Returns Buck's classpath. */
  public static ImmutableList<Path> getClasspath() throws IOException {
    String classpathFromEnv = getBuckClasspathFromEnvVarOrNull();
    if (classpathFromEnv != null) {
      Optional<String> buckTestClasspath = getBuckTestClasspath();
      Stream<Path> classpathStream =
          Arrays.stream(classpathFromEnv.split(File.pathSeparator)).map(Paths::get);
      if (buckTestClasspath.isPresent()) {
        classpathStream =
            Streams.concat(
                classpathStream, readClasspaths(Paths.get(buckTestClasspath.get())).stream());
      }
      return classpathStream.collect(ImmutableList.toImmutableList());
    }
    return getBuckClasspathForIntellij();
  }

  /**
   * Return Buck's classpath when running under Intellij. Use getClasspath() or
   * getBootstrapClasspath() instead.
   */
  public static ImmutableList<Path> getBuckClasspathForIntellij() throws IOException {
    ImmutableList.Builder<Path> classPathEntries = ImmutableList.builder();
    Path productionPath = getClassLocation(BuckClasspath.class);
    classPathEntries.add(productionPath.toAbsolutePath());
    Path testPath = productionPath.resolve("../../test/buck").normalize();
    classPathEntries.add(testPath.toAbsolutePath());
    classPathEntries.addAll(
        filterAntClasspaths(readClasspaths(Paths.get("programs", "classpaths"))));
    classPathEntries.addAll(
        filterAntClasspaths(readClasspaths(Paths.get("programs", "test_classpaths"))));
    return classPathEntries.build();
  }

  private static Path getClassLocation(Class<?> clazz) {
    // Move along, no ridiculous hacks here...
    // Should be something like file:/Users/you/buck/intellij-out/classes/production/buck/
    URL myLocation = clazz.getProtectionDomain().getCodeSource().getLocation();
    Preconditions.checkState(
        myLocation.getProtocol().equals("file"),
        "Don't know how to launch the worker process from %s.",
        myLocation);

    // CLASSPATH entries need to be absolute paths, mostly because the JVM gets exec'd with
    // IntelliJ's CWD when running tests from there.
    try {
      return new File(myLocation.toURI()).toPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static ImmutableList<Path> filterAntClasspaths(ImmutableList<Path> classpaths) {
    return classpaths
        .stream()
        .filter(
            path ->
                !path.startsWith("src") && !path.startsWith("src-gen") && !path.startsWith("build"))
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableList<Path> readClasspaths(Path classpathsFile) throws IOException {
    return Files.readAllLines(classpathsFile)
        .stream()
        .filter(line -> !line.startsWith("#"))
        .map(Paths::get)
        .map(Path::toAbsolutePath)
        .collect(ImmutableList.toImmutableList());
  }
}
