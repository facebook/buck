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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.HostFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.annotation.Nullable;

public abstract class AbstractCommandOptions {

  @VisibleForTesting static final String HELP_LONG_ARG = "--help";

  /**
   * This value should never be read. {@link VerbosityParser} should be used instead.
   * args4j requires that all options that could be passed in are listed as fields, so we include
   * this field so that {@code --verbose} is universally available to all commands.
   */
  @Option(
      name = VerbosityParser.VERBOSE_LONG_ARG,
      aliases = { VerbosityParser.VERBOSE_SHORT_ARG },
      usage = "Specify a number between 1 and 10.")
  @SuppressWarnings("unused")
  private int verbosityLevel = -1;

  @Option(
      name = "--no-cache",
      usage = "Whether to ignore the [cache] declared in .buckconfig.")
  private boolean noCache = false;

  @Option(
      name = HELP_LONG_ARG,
      usage = "Prints the available options and exits.")
  private boolean help = false;

  private final BuckConfig buckConfig;

  @Nullable // Lazily loaded via getCommandLineBuildTargetNormalizer().
  private CommandLineBuildTargetNormalizer commandLineBuildTargetNormalizer;

  private final static String localPropertiesPath = "local.properties";

  AbstractCommandOptions(BuckConfig buckConfig) {
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
  }

  /** @return {code true} if the {@code [cache]} in {@code .buckconfig} should be ignored. */
  public boolean isNoCache() {
    return noCache;
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

  protected CommandLineBuildTargetNormalizer getCommandLineBuildTargetNormalizer() {
    if (commandLineBuildTargetNormalizer == null) {
      commandLineBuildTargetNormalizer = new CommandLineBuildTargetNormalizer(buckConfig);
    }
    return commandLineBuildTargetNormalizer;
  }

  /** @return androidSdkDir */
  public static Optional<File> findAndroidSdkDir(ProjectFilesystem projectFilesystem) {
    Optional<File> androidSdkDir = findDirectoryByPropertiesThenEnvironmentVariable(
          projectFilesystem,
          "sdk.dir",
          "ANDROID_SDK",
          "ANDROID_HOME");
    if (androidSdkDir.isPresent()) {
      Preconditions.checkArgument(androidSdkDir.get().isDirectory(),
          "The location of your Android SDK %s must be a directory",
          androidSdkDir.get());
    }
    return androidSdkDir;
  }

  /** @return androidNdkDir */
  protected Optional<File> findAndroidNdkDir(ProjectFilesystem projectFilesystem) {
    Optional<File> path =
      findDirectoryByPropertiesThenEnvironmentVariable(
          projectFilesystem,
          "ndk.dir",
          "ANDROID_NDK");
    if (path.isPresent()) {
      validateNdkVersion(projectFilesystem, path.get());
    }
    return path;
  }

  @VisibleForTesting
  void validateNdkVersion(ProjectFilesystem projectFilesystem, File ndkPath) {
    Optional<String> minVersion = this.buckConfig.getMinimumNdkVersion();
    Optional<String> maxVersion = this.buckConfig.getMaximumNdkVersion();

    if (minVersion.isPresent() && maxVersion.isPresent()) {
      File versionPath = new File(ndkPath, "RELEASE.TXT");
      Optional<String> contents = projectFilesystem.readFirstLineFromFile(versionPath);
      String version;

      if (contents.isPresent()) {
        version = new StringTokenizer(contents.get()).nextToken();
      } else {
        throw new HumanReadableException(
            "Failed to read NDK version from %s", versionPath.getPath());
      }

      // Example forms: r8, r8b, r9
      if (version.length() < 2) {
        throw new HumanReadableException("Invalid NDK version: %s", version);
      }

      if (version.compareTo(minVersion.get()) < 0 || version.compareTo(maxVersion.get()) > 0) {
        throw new HumanReadableException(
            "Supported NDK versions are between %s and %s but Buck is configured to use %s from %s",
            minVersion.get(),
            maxVersion.get(),
            version,
            ndkPath.getAbsolutePath());
      }
    } else if (minVersion.isPresent() || maxVersion.isPresent()) {
      throw new HumanReadableException(
          "Either both min_version and max_version are provided or neither are");
    }
  }

  /**
   * @param propertyName The name of the property to look for in local.properties.
   * @param environmentVariables The name of the environment variables to try.
   * @return If present, the value is confirmed to be a directory.
   */
  public static Optional<File> findDirectoryByPropertiesThenEnvironmentVariable(
      ProjectFilesystem projectFilesystem,
      String propertyName,
      String... environmentVariables) {
    Optional<Properties> localProperties;
    try {
      localProperties = Optional.of(projectFilesystem.readPropertiesFile(localPropertiesPath));
    } catch (FileNotFoundException e) {
      localProperties = Optional.absent();
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Couldn't read properties file [%s].", localPropertiesPath),
          e);
    }

    return
      findDirectoryByPropertiesThenEnvironmentVariable(
          localProperties,
          new HostFilesystem(),
          System.getenv(),
          propertyName,
          environmentVariables);
  }

  @VisibleForTesting
  static Optional<File> findDirectoryByPropertiesThenEnvironmentVariable(
      Optional<Properties> localProperties,
      HostFilesystem hostFilesystem,
      Map<String, String> systemEnvironment,
      String propertyName,
      String... environmentVariables) {
    // First, try to find a value in local.properties using the specified propertyName.
    Path dirPath = null;
    if (localProperties.isPresent()) {
      String propertyValue = localProperties.get().getProperty(propertyName);
      if (propertyValue != null) {
        dirPath = Paths.get(propertyValue);
      }
    }

    String dirPathEnvironmentVariable = null;

    // If dirPath is not set, try each of the environment variables, in order, to find it.
    for (String environmentVariable : environmentVariables) {
      if (dirPath == null) {
        String environmentVariableValue = systemEnvironment.get(environmentVariable);
        if (environmentVariableValue != null) {
          dirPath = Paths.get(environmentVariableValue);
          dirPathEnvironmentVariable = environmentVariable;
        }
      } else {
        break;
      }
    }

    // If a dirPath was found, verify that it maps to a directory before returning it.
    if (dirPath == null) {
      return Optional.absent();
    } else {
      if (!hostFilesystem.isDirectory(dirPath)) {
        String message;
        if (dirPathEnvironmentVariable != null) {
          message = String.format(
              "Environment variable %s points to invalid path [%s].",
              dirPathEnvironmentVariable,
              dirPath);
        } else {
          message = String.format(
              "Properties file %s contains invalid path [%s] for key %s.",
              localPropertiesPath,
              dirPath,
              propertyName);
        }
        throw new RuntimeException(message);
      }
      return Optional.of(dirPath.toFile());
    }
  }

  protected Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      ProjectFilesystem projectFilesystem, DependencyGraph dependencyGraph, BuckEventBus eventBus) {
    return Build.findAndroidPlatformTarget(
        dependencyGraph,
        findAndroidSdkDir(projectFilesystem),
        eventBus);
  }
}
