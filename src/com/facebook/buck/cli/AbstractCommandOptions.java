/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.command.Build;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Properties;

public abstract class AbstractCommandOptions {

  @VisibleForTesting static final String HELP_LONG_ARG = "--help";
  @VisibleForTesting static final String VERBOSE_LONG_ARG = "--verbose";
  @VisibleForTesting static final String VERBOSE_SHORT_ARG = "-v";

  @Option(
      name = HELP_LONG_ARG,
      usage = "Prints the available options and exits.")
  private boolean help = false;

  /** Verbosity level to use when running Buck. */
  @Option(
      name = VERBOSE_LONG_ARG,
      aliases = { VERBOSE_SHORT_ARG },
      usage = "Specify a number between 1 and 10.")
  private int verbosityLevel = Verbosity.STANDARD_INFORMATION.ordinal();

  private final BuckConfig buckConfig;

  AbstractCommandOptions(BuckConfig buckConfig) {
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
  }

  protected BuckConfig getBuckConfig() {
    return buckConfig;
  }

  public Iterable<String> getDefaultIncludes() {
    return this.buckConfig.getDefaultIncludes();
  }

  public boolean showHelp() {
    return help;
  }

  public Verbosity getVerbosity() {
    if (verbosityLevel >= 8) {
      return Verbosity.ALL;
    } else if (verbosityLevel >= 5) {
      return Verbosity.COMMANDS_AND_OUTPUT;
    } else if (verbosityLevel >= 2) {
      return Verbosity.COMMANDS;
    } else if (verbosityLevel >= 1) {
      return Verbosity.STANDARD_INFORMATION;
    } else {
      return Verbosity.SILENT;
    }
  }

  /** @return androidSdkDir */
  protected Optional<File> findAndroidSdkDir() {
    Optional<File> androidSdkDir = findDirectoryByPropertiesThenEnvironmentVariable(
        "sdk.dir", "ANDROID_SDK", "ANDROID_HOME");
    if (androidSdkDir.isPresent()) {
      Preconditions.checkArgument(androidSdkDir.get().isDirectory(),
          "The location of your Android SDK %s must be a directory",
          androidSdkDir.get());
    }
    return androidSdkDir;
  }

  /** @return androidNdkDir */
  protected Optional<File> findAndroidNdkDir() {
    return findDirectoryByPropertiesThenEnvironmentVariable("ndk.dir", "ANDROID_NDK");
  }

  /**
   * @param propertyName The name of the property to look for in local.properties.
   * @param environmentVariable The name of the environment variable to try.
   * @return If present, the value is confirmed to be a directory.
   */
  private Optional<File> findDirectoryByPropertiesThenEnvironmentVariable(
      String propertyName,
      String... environmentVariables) {
    // First, try to find a value in local.properties using the specified propertyName.
    String dirPath = null;
    File propertiesFile = new File("local.properties");
    if (propertiesFile.exists()) {
      Properties localProperties = new Properties();
      try {
        localProperties.load(new FileReader(propertiesFile));
      } catch (IOException e) {
        throw new RuntimeException(
            "Failed reading properties from " + propertiesFile.getAbsolutePath(),
            e);
      }
      dirPath = localProperties.getProperty(propertyName);
    }

    // If dirPath is not set, try each of the environment variables, in order, to find it.
    for (String environmentVariable : environmentVariables) {
      if (dirPath == null) {
        dirPath = System.getenv(environmentVariable);
      } else {
        break;
      }
    }

    // If a dirPath was found, verify that it maps to a directory before returning it.
    if (dirPath == null) {
      return Optional.absent();
    } else {
      File directory = new File(dirPath);
      if (!directory.isDirectory()) {
        throw new RuntimeException(
            directory.getAbsolutePath() + " was not a directory when trying to find " +
              propertyName);
      }
      return Optional.of(directory);
    }
  }

  protected ExecutionContext createExecutionContext(
      AbstractCommandRunner<?> commandRunner,
      File projectDirectoryRoot,
      DependencyGraph dependencyGraph) {
    return new ExecutionContext(
        getVerbosity(),
        projectDirectoryRoot,
        findAndroidPlatformTarget(dependencyGraph, commandRunner.stdErr),
        findAndroidNdkDir(),
        commandRunner.ansi,
        false /* isCodeCoverageEnabled */,
        false /* isDebugEnabled */,
        commandRunner.stdOut,
        commandRunner.stdErr);
  }

  protected Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      DependencyGraph dependencyGraph, PrintStream stdErr) {
    return Build.findAndroidPlatformTarget(dependencyGraph, findAndroidSdkDir(), stdErr);
  }
}
