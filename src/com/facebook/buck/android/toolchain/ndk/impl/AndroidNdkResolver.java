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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.common.BaseAndroidToolchainResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
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

public class AndroidNdkResolver extends BaseAndroidToolchainResolver {

  private static final Logger LOG = Logger.get(AndroidNdkResolver.class);

  /** Android NDK versions starting with this number are not supported. */
  private static final String NDK_MIN_UNSUPPORTED_VERSION = "18";

  // Pre r11 NDKs store the version at RELEASE.txt.
  @VisibleForTesting static final String NDK_PRE_R11_VERSION_FILENAME = "RELEASE.TXT";
  // Post r11 NDKs store the version at source.properties.
  @VisibleForTesting static final String NDK_POST_R11_VERSION_FILENAME = "source.properties";

  @VisibleForTesting
  static final String NDK_NOT_FOUND_MESSAGE =
      "Android NDK could not be found. Make sure to set "
          + "one of these  environment variables: ANDROID_NDK_REPOSITORY, ANDROID_NDK, or NDK_HOME "
          + "or ndk.ndk_path or ndk.ndk_repository_path in your .buckconfig";

  @VisibleForTesting
  static final String NDK_TARGET_VERSION_IS_EMPTY_MESSAGE =
      "buckconfig entry [ndk] ndk_version is an empty string.";

  private final Optional<String> targetNdkVersion;
  private final Optional<Path> ndk;

  private Optional<String> ndkErrorMessage = Optional.empty();

  public AndroidNdkResolver(
      FileSystem fileSystem, ImmutableMap<String, String> environment, AndroidBuckConfig config) {
    super(fileSystem, environment);
    this.targetNdkVersion = config.getNdkVersion();
    this.ndk = findNdk(config);
  }

  public Path getNdkOrThrow() {
    if (!ndk.isPresent() && ndkErrorMessage.isPresent()) {
      throw new HumanReadableException(ndkErrorMessage.get());
    }
    return ndk.get();
  }

  public Optional<String> getNdkVersion() {
    if (!ndk.isPresent()) {
      return Optional.empty();
    }
    return findNdkVersion(ndk.get());
  }

  private Optional<Path> findNdk(AndroidBuckConfig config) {
    try {
      Optional<Path> ndkRepositoryPath =
          findFirstDirectory(ImmutableList.of(getEnvironmentVariable("ANDROID_NDK_REPOSITORY")));
      if (ndkRepositoryPath.isPresent()) {
        return findNdkFromRepository(ndkRepositoryPath.get());
      }
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
    }
    try {
      Optional<Path> ndkDirectoryPath =
          findFirstDirectory(
              ImmutableList.of(
                  getEnvironmentVariable("ANDROID_NDK"), getEnvironmentVariable("NDK_HOME")));
      if (ndkDirectoryPath.isPresent()) {
        return findNdkFromDirectory(ndkDirectoryPath.get());
      }
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
    }
    try {
      Optional<Path> ndkRepositoryPath =
          findFirstDirectory(
              ImmutableList.of(
                  new Pair<String, Optional<String>>(
                      "ndk.ndk_repository_path", config.getNdkRepositoryPath())));
      if (ndkRepositoryPath.isPresent()) {
        return findNdkFromRepository(ndkRepositoryPath.get());
      }
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
    }
    try {
      Optional<Path> ndkDirectoryPath =
          findFirstDirectory(
              ImmutableList.of(
                  new Pair<String, Optional<String>>("ndk.ndk_path", config.getNdkPath())));
      if (ndkDirectoryPath.isPresent()) {
        return findNdkFromDirectory(ndkDirectoryPath.get());
      }
    } catch (RuntimeException e) {
      ndkErrorMessage = Optional.of(e.getMessage());
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
              "Unable to read contents of Android ndk.ndk_repository_path or "
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
            .filter(
                pair ->
                    versionComparator.compare(pair.getSecond().get(), NDK_MIN_UNSUPPORTED_VERSION)
                        < 0)
            .sorted(
                (o1, o2) -> versionComparator.compare(o2.getSecond().get(), o1.getSecond().get()))
            .collect(Collectors.toList());

    if (availableNdks.isEmpty()) {
      ndkErrorMessage =
          Optional.of(
              repository
                  + " does not contain any valid Android NDK. Make"
                  + " sure to specify ANDROID_NDK_REPOSITORY or ndk.ndk_repository_path.");
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

    LOG.debug("Using Android NDK version %s", availableNdks.get(0).getSecond().get());

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

  private boolean versionsMatch(String expected, String candidate) {
    return !(Strings.isNullOrEmpty(expected) || Strings.isNullOrEmpty(candidate))
        && candidate.startsWith(expected);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof AndroidNdkResolver)) {
      return false;
    }

    AndroidNdkResolver that = (AndroidNdkResolver) other;

    return Objects.equals(targetNdkVersion, that.targetNdkVersion) && Objects.equals(ndk, that.ndk);
  }

  @Override
  public String toString() {
    return String.format(
        "%s targetNdkVersion=%s, " + "AndroidNdkDir=%s",
        super.toString(), targetNdkVersion, (ndk.isPresent()) ? (ndk.get()) : "NDK not available");
  }

  @Override
  public int hashCode() {
    return Objects.hash(targetNdkVersion);
  }
}
