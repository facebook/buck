/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.android;

import com.facebook.buck.model.Pair;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/** Utility class used for resolving the location of Android specific directories. */
public class DefaultAndroidDirectoryResolver implements AndroidDirectoryResolver {
  // Pre r11 NDKs store the version at RELEASE.txt.
  @VisibleForTesting static final String NDK_PRE_R11_VERSION_FILENAME = "RELEASE.TXT";
  // Post r11 NDKs store the version at source.properties.
  @VisibleForTesting static final String NDK_POST_R11_VERSION_FILENAME = "source.properties";

  @VisibleForTesting
  static final String SDK_NOT_FOUND_MESSAGE =
      "Android SDK could not be found. Make sure to set "
          + "one of these environment variables: ANDROID_SDK, ANDROID_HOME";

  @VisibleForTesting
  static final String TOOLS_NEED_SDK_MESSAGE =
      "Android SDK Build tools require Android SDK. "
          + "Make sure one of these environment variables was set: ANDROID_SDK, ANDROID_HOME";

  @VisibleForTesting
  static final String NDK_NOT_FOUND_MESSAGE =
      "Android NDK could not be found. Make sure to set "
          + "one of these  environment variables: ANDROID_NDK_REPOSITORY, ANDROID_NDK or NDK_HOME]";

  @VisibleForTesting
  static final String NDK_TARGET_VERSION_IS_EMPTY_MESSAGE =
      "buckconfig entry [ndk] ndk_version is an empty string.";

  public static final ImmutableSet<String> BUILD_TOOL_PREFIXES =
      ImmutableSet.of("android-", "build-tools-");

  private final FileSystem fileSystem;
  private final ImmutableMap<String, String> environment;
  private final Optional<String> targetBuildToolsVersion;
  private final Optional<String> targetNdkVersion;

  private Optional<String> sdkErrorMessage;
  private Optional<String> buildToolsErrorMessage;
  private Optional<String> ndkErrorMessage;
  private final Optional<Path> sdk;
  private final Optional<Path> buildTools;
  private final Optional<Path> ndk;

  public DefaultAndroidDirectoryResolver(
      FileSystem fileSystem,
      ImmutableMap<String, String> environment,
      Optional<String> targetBuildToolsVersion,
      Optional<String> targetNdkVersion) {
    this.fileSystem = fileSystem;
    this.environment = environment;
    this.targetBuildToolsVersion = targetBuildToolsVersion;
    this.targetNdkVersion = targetNdkVersion;

    this.sdkErrorMessage = Optional.empty();
    this.buildToolsErrorMessage = Optional.empty();
    this.ndkErrorMessage = Optional.empty();
    this.sdk = findSdk();
    this.buildTools = findBuildTools();
    this.ndk = findNdk();
  }

  @Override
  public Path getSdkOrThrow() {
    if (!sdk.isPresent() && sdkErrorMessage.isPresent()) {
      throw new HumanReadableException(sdkErrorMessage.get());
    }
    return sdk.get();
  }

  @Override
  public Path getBuildToolsOrThrow() {
    if (!buildTools.isPresent() && buildToolsErrorMessage.isPresent()) {
      throw new HumanReadableException(buildToolsErrorMessage.get());
    }
    return buildTools.get();
  }

  @Override
  public Path getNdkOrThrow() {
    if (!ndk.isPresent() && ndkErrorMessage.isPresent()) {
      throw new HumanReadableException(ndkErrorMessage.get());
    }
    return ndk.get();
  }

  @Override
  public Optional<Path> getSdkOrAbsent() {
    return sdk;
  }

  @Override
  public Optional<Path> getNdkOrAbsent() {
    return ndk;
  }

  @Override
  public Optional<String> getNdkVersion() {
    Optional<Path> ndkPath = getNdkOrAbsent();
    if (!ndkPath.isPresent()) {
      return Optional.empty();
    }
    return findNdkVersion(ndkPath.get());
  }

  private Optional<Path> findSdk() {
    Optional<Path> sdkPath;
    try {
      sdkPath = findDirectoryByEnvironmentVariables("ANDROID_SDK", "ANDROID_HOME");
    } catch (RuntimeException e) {
      sdkErrorMessage = Optional.of(e.getMessage());
      return Optional.empty();
    }

    if (!sdkPath.isPresent()) {
      sdkErrorMessage = Optional.of(SDK_NOT_FOUND_MESSAGE);
    }
    return sdkPath;
  }

  private Optional<Path> findDirectoryByEnvironmentVariables(String... environmentVariables) {
    Path dirPath = null;
    String dirPathEnvironmentVariable = null;

    // First, try to find a value in each of the environment variables, in order.
    for (String environmentVariable : environmentVariables) {
      String environmentVariableValue = environment.get(environmentVariable);
      if (environmentVariableValue != null) {
        dirPath = fileSystem.getPath(environmentVariableValue);
        dirPathEnvironmentVariable = environmentVariable;
        break;
      }
    }

    // If a dirPath was found, verify that it maps to a directory before returning it.
    if (dirPath == null) {
      return Optional.empty();
    }
    if (!Files.isDirectory(dirPath)) {
      throw new RuntimeException(
          String.format(
              "Environment variable '%s' points to a path that is not a directory: '%s'.",
              dirPathEnvironmentVariable, dirPath));
    }
    return Optional.of(dirPath);
  }

  private Optional<Path> findBuildTools() {
    if (!sdk.isPresent()) {
      buildToolsErrorMessage = Optional.of(TOOLS_NEED_SDK_MESSAGE);
      return Optional.empty();
    }
    final Path sdkDir = sdk.get();
    final Path toolsDir = sdkDir.resolve("build-tools");

    if (toolsDir.toFile().isDirectory()) {
      // In older versions of the ADT that have been upgraded via the SDK manager, the build-tools
      // directory appears to contain subfolders of the form "17.0.0". However, newer versions of
      // the ADT that are downloaded directly from http://developer.android.com/ appear to have
      // subfolders of the form android-4.2.2. There also appear to be cases where subfolders
      // are named build-tools-18.0.0. We need to support all of these scenarios.
      File[] directories;
      try {
        directories =
            toolsDir
                .toFile()
                .listFiles(
                    pathname -> {
                      if (!pathname.isDirectory()) {
                        return false;
                      }
                      String version = stripBuildToolsPrefix(pathname.getName());
                      if (!VersionStringComparator.isValidVersionString(version)) {
                        throw new HumanReadableException(
                            "%s in %s is not a valid build tools directory.%n"
                                + "Build tools directories should be follow the naming scheme: "
                                + "android-<VERSION>, build-tools-<VERSION>, or <VERSION>. Please remove "
                                + "directory %s.",
                            pathname.getName(), buildTools, pathname.getName());
                      }
                      if (targetBuildToolsVersion.isPresent()) {
                        return targetBuildToolsVersion.get().equals(pathname.getName());
                      }
                      return true;
                    });
      } catch (HumanReadableException e) {
        buildToolsErrorMessage = Optional.of(e.getHumanReadableErrorMessage());
        return Optional.empty();
      }

      if (targetBuildToolsVersion.isPresent()) {
        if (directories.length == 0) {
          buildToolsErrorMessage = unableToFindTargetBuildTools();
          return Optional.empty();
        } else {
          return Optional.of(directories[0].toPath());
        }
      }

      // We aren't looking for a specific version, so we pick the newest version
      final VersionStringComparator comparator = new VersionStringComparator();
      File newestBuildDir = null;
      String newestBuildDirVersion = null;
      for (File directory : directories) {
        String currentDirVersion = stripBuildToolsPrefix(directory.getName());
        if (newestBuildDir == null
            || newestBuildDirVersion == null
            || comparator.compare(newestBuildDirVersion, currentDirVersion) < 0) {
          newestBuildDir = directory;
          newestBuildDirVersion = currentDirVersion;
        }
      }
      if (newestBuildDir == null) {
        buildToolsErrorMessage =
            Optional.of(
                buildTools
                    + " was empty, but should have "
                    + "contained a subdirectory with build tools. Install them using the Android "
                    + "SDK Manager ("
                    + toolsDir.getParent().resolve("tools").resolve("android")
                    + ").");
        return Optional.empty();
      }
      return Optional.of(newestBuildDir.toPath());
    }
    if (targetBuildToolsVersion.isPresent()) {
      // We were looking for a specific version, but we aren't going to find it at this point since
      // nothing under platform-tools was versioned.
      buildToolsErrorMessage = unableToFindTargetBuildTools();
      return Optional.empty();
    }
    // Build tools used to exist inside of platform-tools, so fallback to that.
    return Optional.of(sdkDir.resolve("platform-tools"));
  }

  private Optional<Path> findNdk() {
    Optional<Path> repository = Optional.empty();
    try {
      repository = findDirectoryByEnvironmentVariables("ANDROID_NDK_REPOSITORY");
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
    }
    if (repository.isPresent()) {
      return findNdkFromRepository(repository.get());
    }

    Optional<Path> directory = Optional.empty();
    try {
      directory = findDirectoryByEnvironmentVariables("ANDROID_NDK", "NDK_HOME");
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
    }
    if (directory.isPresent()) {
      return findNdkFromDirectory(directory.get());
    }

    if (!ndkErrorMessage.isPresent()) {
      ndkErrorMessage = Optional.of(NDK_NOT_FOUND_MESSAGE);
    }
    return Optional.empty();
  }

  private Optional<Path> findNdkFromDirectory(Path directory) {
    Optional<String> version = findNdkVersion(directory);
    if (!version.isPresent() && ndkErrorMessage.isPresent()) {
      return Optional.empty();
    } else if (version.isPresent()) {
      if (targetNdkVersion.isPresent() && !versionsMatch(targetNdkVersion.get(), version.get())) {
        ndkErrorMessage =
            Optional.of(
                "Buck is configured to use Android NDK version "
                    + targetNdkVersion.get()
                    + " at ndk.dir or ANDROID_NDK or NDK_HOME. The found version "
                    + "is "
                    + version.get()
                    + " located at "
                    + directory);
        return Optional.empty();
      }
    } else {
      ndkErrorMessage = Optional.of("Failed to read NDK version from " + directory + ".");
      return Optional.empty();
    }
    return Optional.of(directory);
  }

  private Optional<Path> findNdkFromRepository(Path repository) {
    ImmutableSet<Path> repositoryContents;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(repository)) {
      repositoryContents = ImmutableSet.copyOf(stream);
    } catch (IOException e) {
      ndkErrorMessage =
          Optional.of(
              "Unable to read contents of Android ndk.repository or "
                  + "ANDROID_NDK_REPOSITORY at "
                  + repository);
      return Optional.empty();
    }

    VersionStringComparator versionComparator = new VersionStringComparator();
    List<Pair<Path, Optional<String>>> availableNdks =
        repositoryContents
            .stream()
            .filter(Files::isDirectory)
            // Pair of path to version number
            .map(p -> new Pair<>(p, findNdkVersion(p)))
            .filter(pair -> pair.getSecond().isPresent())
            .sorted(
                (o1, o2) -> versionComparator.compare(o2.getSecond().get(), o1.getSecond().get()))
            .collect(Collectors.toList());

    if (availableNdks.isEmpty()) {
      ndkErrorMessage =
          Optional.of(
              repository
                  + " does not contain any valid Android NDK. Make"
                  + " sure to specify ANDROID_NDK_REPOSITORY or ndk.repository.");
      return Optional.empty();
    }

    if (targetNdkVersion.isPresent()) {
      if (targetNdkVersion.get().isEmpty()) {
        ndkErrorMessage = Optional.of(NDK_TARGET_VERSION_IS_EMPTY_MESSAGE);
        return Optional.empty();
      }

      Optional<Path> targetNdkPath =
          availableNdks
              .stream()
              .filter(p -> versionsMatch(targetNdkVersion.get(), p.getSecond().get()))
              .map(Pair::getFirst)
              .findFirst();
      if (targetNdkPath.isPresent()) {
        return targetNdkPath;
      }
      ndkErrorMessage =
          Optional.of(
              "Target NDK version "
                  + targetNdkVersion.get()
                  + " is not "
                  + "available. The following versions are available: "
                  + availableNdks
                      .stream()
                      .map(Pair::getSecond)
                      .map(Optional::get)
                      .collect(Collectors.joining(", ")));
      return Optional.empty();
    }

    return Optional.of(availableNdks.get(0).getFirst());
  }

  /**
   * The method returns the NDK version of a path.
   *
   * @param ndkDirectory Path to the folder that contains the NDK.
   * @return A string containing the NDK version or absent.
   */
  public static Optional<String> findNdkVersionFromDirectory(Path ndkDirectory) {
    Path newNdk = ndkDirectory.resolve(NDK_POST_R11_VERSION_FILENAME);
    Path oldNdk = ndkDirectory.resolve(NDK_PRE_R11_VERSION_FILENAME);
    boolean newNdkPathFound = Files.exists(newNdk);
    boolean oldNdkPathFound = Files.exists(oldNdk);

    if (newNdkPathFound && oldNdkPathFound) {
      throw new HumanReadableException(
          "Android NDK directory "
              + ndkDirectory
              + " can not "
              + "contain both properties files. Remove source.properties or RELEASE.TXT.");
    } else if (newNdkPathFound) {
      Properties sourceProperties = new Properties();
      try (FileInputStream fileStream = new FileInputStream(newNdk.toFile())) {
        sourceProperties.load(fileStream);
        return Optional.ofNullable(sourceProperties.getProperty("Pkg.Revision"));
      } catch (IOException e) {
        throw new HumanReadableException("Failed to read NDK version from " + newNdk + ".");
      }
    } else if (oldNdkPathFound) {
      try (BufferedReader reader = Files.newBufferedReader(oldNdk, Charsets.UTF_8)) {
        // Android NDK r10e for Linux is mislabeled as r10e-rc4 instead of r10e. This is a work
        // around since we should consider them equivalent.
        return Optional.ofNullable(reader.readLine().split("\\s+")[0].replace("r10e-rc4", "r10e"));
      } catch (IOException e) {
        throw new HumanReadableException("Failed to read NDK version from " + oldNdk + ".");
      }
    } else {
      throw new HumanReadableException(
          ndkDirectory + " does not contain a valid properties " + "file for Android NDK.");
    }
  }

  public Optional<String> findNdkVersion(Path ndkDirectory) {
    try {
      return findNdkVersionFromDirectory(ndkDirectory);
    } catch (HumanReadableException e) {
      ndkErrorMessage = Optional.of(e.getHumanReadableErrorMessage());
      return Optional.empty();
    }
  }

  private static String stripBuildToolsPrefix(String name) {
    for (String prefix : BUILD_TOOL_PREFIXES) {
      if (name.startsWith(prefix)) {
        return name.substring(prefix.length());
      }
    }
    return name;
  }

  private Optional<String> unableToFindTargetBuildTools() {
    return Optional.of(
        "Unable to find build-tools version "
            + targetBuildToolsVersion.get()
            + ", which is specified by your config.  Please see "
            + "https://buckbuild.com/concept/buckconfig.html#android.build_tools_version for more "
            + "details about the setting.  To install the correct version of the tools, run `"
            + Escaper.escapeAsShellString(sdk.get().resolve("tools/android").toString())
            + " update "
            + "sdk --force --no-ui --all --filter build-tools-"
            + targetBuildToolsVersion.get()
            + "`");
  }

  private boolean versionsMatch(String expected, String candidate) {
    return !(Strings.isNullOrEmpty(expected) || Strings.isNullOrEmpty(candidate))
        && candidate.startsWith(expected);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof DefaultAndroidDirectoryResolver)) {
      return false;
    }

    DefaultAndroidDirectoryResolver that = (DefaultAndroidDirectoryResolver) other;

    return Objects.equals(targetBuildToolsVersion, that.targetBuildToolsVersion)
        && Objects.equals(targetNdkVersion, that.targetNdkVersion)
        && Objects.equals(getNdkOrAbsent(), that.getNdkOrAbsent());
  }

  @Override
  public String toString() {
    return String.format(
        "%s targetBuildToolsVersion=%s, targetNdkVersion=%s, "
            + "AndroidSdkDir=%s, AndroidBuildToolsDir=%s, AndroidNdkDir=%s",
        super.toString(),
        targetBuildToolsVersion,
        targetNdkVersion,
        (sdk.isPresent()) ? (sdk.get()) : "SDK not available",
        (buildTools.isPresent()) ? (buildTools.get()) : "Build tools not available",
        (ndk.isPresent()) ? (ndk.get()) : "NDK not available");
  }

  @Override
  public int hashCode() {
    return Objects.hash(targetBuildToolsVersion, targetNdkVersion);
  }
}
