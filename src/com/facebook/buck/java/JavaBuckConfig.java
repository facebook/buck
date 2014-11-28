/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import static com.facebook.buck.util.ProcessExecutor.Option.EXPECTING_STD_OUT;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A java-specific "view" of BuckConfig.
 */
public class JavaBuckConfig {
  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";
  private final BuckConfig delegate;

  public JavaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public JavacOptions getDefaultJavacOptions(ProcessExecutor processExecutor)
      throws InterruptedException {
    Optional<String> sourceLevel = delegate.getValue("java", "source_level");
    Optional<String> targetLevel = delegate.getValue("java", "target_level");

    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("java");
    ImmutableMap.Builder<String, String> bootclasspaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("bootclasspath-")) {
        bootclasspaths.put(entry.getKey().substring("bootclasspath-".length()), entry.getValue());
      }
    }

    return JavacOptions.builderForUseInJavaBuckConfig()
        .setJavaCompilerEnvironment(getJavaCompilerEnvironment(processExecutor))
        .setSourceLevel(sourceLevel.or(TARGETED_JAVA_VERSION))
        .setTargetLevel(targetLevel.or(TARGETED_JAVA_VERSION))
        .setBootclasspathMap(bootclasspaths.build())
        .build();
  }

  private JavaCompilerEnvironment getJavaCompilerEnvironment(ProcessExecutor processExecutor)
      throws InterruptedException {
    Optional<Path> javac = getJavac();
    Optional<JavacVersion> javacVersion = Optional.absent();
    if (javac.isPresent()) {
      javacVersion = Optional.of(getJavacVersion(processExecutor, javac.get()));
    }
    return new JavaCompilerEnvironment(
        javac,
        javacVersion);
  }

  @VisibleForTesting
  Optional<Path> getJavac() {
    Optional<String> path = delegate.getValue("tools", "javac");
    if (path.isPresent()) {
      File javac = new File(path.get());
      if (!javac.exists()) {
        throw new HumanReadableException("Javac does not exist: " + javac.getPath());
      }
      if (!javac.canExecute()) {
        throw new HumanReadableException("Javac is not executable: " + javac.getPath());
      }
      return Optional.of(javac.toPath());
    }
    return Optional.absent();
  }

  /**
   * @param executor ProcessExecutor to run the java compiler
   * @param javac path to the java compiler
   * @return the version of the passed in java compiler
   */
  private JavacVersion getJavacVersion(ProcessExecutor executor, Path javac)
      throws InterruptedException {
    try {
      ProcessExecutorParams build = ProcessExecutorParams.builder()
          .setCommand(ImmutableList.of(javac.toAbsolutePath().toString(), "-version"))
          .build();
      ProcessExecutor.Result versionResult = executor.launchAndExecute(
          build,
          ImmutableSet.of(EXPECTING_STD_OUT),
          Optional.<String>absent());
      if (versionResult.getExitCode() == 0) {
        return new JavacVersion(versionResult.getStderr().get());
      } else {
        throw new HumanReadableException(versionResult.getStderr().get());
      }
    } catch (IOException e) {
      throw new HumanReadableException("Could not run " + javac + " -version");
    }
  }

}
