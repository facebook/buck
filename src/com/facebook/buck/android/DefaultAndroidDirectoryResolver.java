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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Utility class used for resolving the location of Android specific directories.
 */
public class DefaultAndroidDirectoryResolver implements AndroidDirectoryResolver {
  // Pre r11 NDKs store the version at RELEASE.txt.
  @VisibleForTesting
  static final String NDK_PRE_R11_VERSION_FILENAME = "RELEASE.TXT";
  // Post r11 NDKs store the version at source.properties.
  @VisibleForTesting
  static final String NDK_POST_R11_VERSION_FILENAME = "source.properties";

  @VisibleForTesting
  static final String SDK_NOT_FOUND_MESSAGE = "Android SDK could not be found. Make sure to set " +
      "one of these environment variables: ANDROID_SDK, ANDROID_HOME";

  @VisibleForTesting
  static final String NDK_NOT_FOUND_MESSAGE = "Android NDK could not be found. Make sure to set " +
      "one of these  environment variables: ANDROID_NDK_REPOSITORY, ANDROID_NDK or NDK_HOME]";

  public static final ImmutableSet<String> BUILD_TOOL_PREFIXES =
      ImmutableSet.of("android-", "build-tools-");

  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> targetBuildToolsVersion;
  private final Optional<String> targetNdkVersion;
  private final PropertyFinder propertyFinder;

  private final Supplier<Optional<Path>> sdk;
  private final Supplier<Path> buildTools;
  private final Supplier<Optional<Path>> ndk;

  public DefaultAndroidDirectoryResolver(
      ProjectFilesystem projectFilesystem,
      Optional<String> targetBuildToolsVersion,
      Optional<String> targetNdkVersion,
      PropertyFinder propertyFinder) {
    this.projectFilesystem = projectFilesystem;
    this.targetBuildToolsVersion = targetBuildToolsVersion;
    this.targetNdkVersion = targetNdkVersion;
    this.propertyFinder = propertyFinder;

    this.sdk =
        Suppliers.memoize(new Supplier<Optional<Path>>() {
          @Override
          public Optional<Path> get() {
            return findSdk();
          }
        });

    this.buildTools =
        Suppliers.memoize(new Supplier<Path>() {
          @Override
          public Path get() {
            return findBuildTools();
          }
        });
    this.ndk =
        Suppliers.memoize(new Supplier<Optional<Path>>() {
          @Override
          public Optional<Path> get() {
            return findNdk();
          }
        });
  }

  @Override
  public Path getSdkOrThrow() {
    Optional<Path> androidSdkDir = getSdkOrAbsent();
    Preconditions.checkState(androidSdkDir.isPresent(), SDK_NOT_FOUND_MESSAGE);
    return androidSdkDir.get();
  }

  @Override
  public Path getBuildToolsOrThrow() {
    return buildTools.get();
  }

  @Override
  public Optional<Path> getSdkOrAbsent() {
    return sdk.get();
  }

  @Override
  public Optional<Path> getNdkOrAbsent() {
    return ndk.get();
  }

  @Override
  public Optional<String> getNdkVersion() {
    Optional<Path> ndkPath = getNdkOrAbsent();
    if (!ndkPath.isPresent()) {
      return Optional.absent();
    }
    return findNdkVersion(ndkPath.get());
  }

  /**
   * The method returns the NDK version of a path.
   * @param ndkDirectory Path to the folder that contains the NDK.
   * @return A string containing the NDK version or absent.
   */
  private Optional<String> findNdkVersion(Path ndkDirectory) {
    Path newNdkPath = ndkDirectory.resolve(NDK_POST_R11_VERSION_FILENAME);
    Path oldNdkPath = ndkDirectory.resolve(NDK_PRE_R11_VERSION_FILENAME);
    boolean newNdkPathFound = Files.exists(newNdkPath);
    boolean oldNdkPathFound = Files.exists(oldNdkPath);

    if (newNdkPathFound && oldNdkPathFound) {
      throw new HumanReadableException("Android NDK directory " + ndkDirectory + " can not " +
          "contain both properties files. Remove source.properties or RELEASE.TXT.");
    } else if (newNdkPathFound) {
      Properties sourceProperties = new Properties();
      try (FileInputStream fileStream = new FileInputStream(newNdkPath.toFile())) {
        sourceProperties.load(fileStream);
        return Optional.fromNullable(sourceProperties.getProperty("Pkg.Revision"));
      } catch (IOException e) {
        throw new HumanReadableException("Failed to read NDK version from " + newNdkPath + ".");
      }
    } else if (oldNdkPathFound) {
      Optional<String> propertiesContents = projectFilesystem.readFirstLineFromFile(oldNdkPath);
      return Optional.of(propertiesContents.get().split("\\s+")[0]);
    } else {
      throw new HumanReadableException(ndkDirectory + " does not contain a valid properties file " +
          "for Android NDK.");
    }
  }

  private Optional<Path> findSdk() {
    Optional<Path> sdkPath = propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
          "sdk.dir",
          "ANDROID_SDK",
          "ANDROID_HOME");

    if (sdkPath.isPresent() && !sdkPath.get().toFile().isDirectory()) {
        throw new HumanReadableException("Android SDK path (" + sdkPath.get() + ") " +
            "is not a directory.");
    }
    return sdkPath;
  }

  private Path findBuildTools() {
    final Path androidSdkDir = getSdkOrThrow();
    final Path buildToolsDir = androidSdkDir.resolve("build-tools");

    if (buildToolsDir.toFile().isDirectory()) {
      // In older versions of the ADT that have been upgraded via the SDK manager, the build-tools
      // directory appears to contain subfolders of the form "17.0.0". However, newer versions of
      // the ADT that are downloaded directly from http://developer.android.com/ appear to have
      // subfolders of the form android-4.2.2. There also appear to be cases where subfolders
      // are named build-tools-18.0.0. We need to support all of these scenarios.

      File[] directories = buildToolsDir.toFile().listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          if (!pathname.isDirectory()) {
            return false;
          }
          String version = stripBuildToolsPrefix(pathname.getName());
          if (!VersionStringComparator.isValidVersionString(version)) {
            throw new HumanReadableException(
                "%s in %s is not a valid build tools directory.%n" +
                    "Build tools directories should be follow the naming scheme: " +
                    "android-<VERSION>, build-tools-<VERSION>, or <VERSION>. Please remove " +
                    "directory %s.",
                pathname.getName(),
                buildToolsDir,
                pathname.getName());
          }
          if (targetBuildToolsVersion.isPresent()) {
            return targetBuildToolsVersion.get().equals(pathname.getName());
          }
          return true;
        }
      });

      if (targetBuildToolsVersion.isPresent()) {
        if (directories.length == 0) {
          throw unableToFindTargetBuildTools();
        } else {
          return directories[0].toPath();
        }
      }

      // We aren't looking for a specific version, so we pick the newest version
      final VersionStringComparator comparator = new VersionStringComparator();
      File newestBuildDir = null;
      String newestBuildDirVersion = null;
      for (File directory : directories) {
        String currentDirVersion = stripBuildToolsPrefix(directory.getName());
         if (newestBuildDir == null || newestBuildDirVersion == null ||
            comparator.compare(newestBuildDirVersion, currentDirVersion) < 0) {
          newestBuildDir = directory;
          newestBuildDirVersion = currentDirVersion;
        }
      }
      if (newestBuildDir == null) {
        throw new HumanReadableException(
                "%s was empty, but should have contained a subdirectory with build tools.%n" +
                    "Install them using the Android SDK Manager (%s).",
            buildToolsDir,
            buildToolsDir.getParent().resolve("tools").resolve("android"));
      }
      return newestBuildDir.toPath();
    }
    if (targetBuildToolsVersion.isPresent()) {
      // We were looking for a specific version, but we aren't going to find it at this point since
      // nothing under platform-tools was versioned.
      throw unableToFindTargetBuildTools();
    }
    // Build tools used to exist inside of platform-tools, so fallback to that.
    return androidSdkDir.resolve("platform-tools");
  }

  private Optional<Path> findNdk() {
    Optional<Path> repository = propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        "ndk.repository",
        "ANDROID_NDK_REPOSITORY");
    if (repository.isPresent()){
      return findNdkFromRepository(repository.get());
    }
    Optional<Path> directory = propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        "ndk.dir",
        "ANDROID_NDK",
        "NDK_HOME");
    if (directory.isPresent()) {
      return findNdkFromDirectory(directory.get());
    }

    return Optional.absent();
  }

  private Optional<Path> findNdkFromDirectory(Path directory) {
    Optional<String> version = findNdkVersion(directory);
    if (version.isPresent()) {
      if (targetNdkVersion.isPresent() && !versionsMatch(targetNdkVersion.get(), version.get())) {
        throw new HumanReadableException("Buck is configured to use Android NDK version " +
            targetNdkVersion.get() + " at ndk.dir or ANDROID_NDK or NDK_HOME. The found version " +
            "is " + version.get() + " located at " + directory);
      }
    } else {
      throw new HumanReadableException("Failed to read NDK version from " + directory + ".");
    }
    return Optional.of(directory);
  }

  private Optional<Path> findNdkFromRepository(Path repository) {
    ImmutableSortedSet<Path> repositoryContents = ImmutableSortedSet.of();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(repository)) {
      repositoryContents = ImmutableSortedSet.copyOf(stream);
    } catch (IOException e) {
      throw new HumanReadableException("Unable to read contents of Android ndk.repository or " +
          "ANDROID_NDK_REPOSITORY at " + repository);
    }

    Optional<Path> ndkPath = Optional.absent();
    if (!repositoryContents.isEmpty()) {
      Optional<String> newestVersion = Optional.absent();
      VersionStringComparator versionComparator = new VersionStringComparator();
      for (Path potentialNdkPath : repositoryContents) {
        if (potentialNdkPath.toFile().isDirectory()) {
          Optional<String> ndkVersion = findNdkVersion(potentialNdkPath);
          if (ndkVersion.isPresent()) {
            if (targetNdkVersion.isPresent()) {
              if (versionsMatch(targetNdkVersion.get(), ndkVersion.get())) {
                return Optional.of(potentialNdkPath);
              }
            } else {
              if (!newestVersion.isPresent() || versionComparator.compare(
                  ndkVersion.get(),
                  newestVersion.get()) > 0) {
                ndkPath = Optional.of(potentialNdkPath);
                newestVersion = Optional.of(ndkVersion.get());
              }
            }
          }
        }
      }
    }
    if (!ndkPath.isPresent()) {
      throw new HumanReadableException("Couldn't find a valid NDK under %s", repository);
    } else if (targetNdkVersion.isPresent()) {
      throw new HumanReadableException("Buck is configured to use Android NDK version " +
          targetNdkVersion.get() + " at ANDROID_NDK_REPOSITORY or ndk.repository but the " +
          "repository does not contain that version.");
    }
    return ndkPath;
  }

  private static String stripBuildToolsPrefix(String name) {
    for (String prefix: BUILD_TOOL_PREFIXES) {
      if (name.startsWith(prefix)) {
        return name.substring(prefix.length());
      }
    }
    return name;
  }

  private HumanReadableException unableToFindTargetBuildTools() {
    throw new HumanReadableException(
        "Unable to find build-tools version %s, which is specified by your config.  Please see " +
            "https://buckbuild.com/concept/buckconfig.html#android.build_tools_version for more " +
            "details about the setting.  To install the correct version of the tools, run " +
            "`%s update sdk --force --no-ui --all --filter build-tools-%s`",
        targetBuildToolsVersion.get(),
        Escaper.escapeAsShellString(getSdkOrThrow().resolve("tools/android").toString()),
        targetBuildToolsVersion.get());
  }

  private boolean versionsMatch(String expected, String candidate) {
    if (Strings.isNullOrEmpty(expected) || Strings.isNullOrEmpty(candidate)) {
      return false;
    }

    return candidate.startsWith(expected);
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

    return
        Objects.equals(projectFilesystem, that.projectFilesystem) &&
        Objects.equals(targetBuildToolsVersion, that.targetBuildToolsVersion) &&
        Objects.equals(targetNdkVersion, that.targetNdkVersion) &&
        Objects.equals(propertyFinder, that.propertyFinder) &&
        Objects.equals(getNdkOrAbsent(), that.getNdkOrAbsent());
  }

  @Override
  public String toString() {
    return String.format(
        "%s projectFilesystem=%s, targetBuildToolsVersion=%s, targetNdkVersion=%s, " +
            "propertyFinder=%s, AndroidSdkDir=%s, AndroidBuildToolsDir=%s, AndroidNdkDir=%s",
        super.toString(),
        projectFilesystem,
        targetBuildToolsVersion,
        targetNdkVersion,
        propertyFinder,
        (sdk.get().isPresent()) ? (sdk.get().get()) : "SDK not available",
        (sdk.get().isPresent()) ? (buildTools.get()) : "Build tools not available",
        (ndk.get().isPresent()) ? (ndk.get()) : "NDK not available");
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        projectFilesystem,
        targetBuildToolsVersion,
        targetNdkVersion,
        propertyFinder);
  }
}
