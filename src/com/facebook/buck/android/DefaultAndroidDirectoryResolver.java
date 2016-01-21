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
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Utility class used for resolving the location of Android specific directories.
 */
public class DefaultAndroidDirectoryResolver implements AndroidDirectoryResolver {

  public static final String ANDROID_VERSION_PREFIX = "android-";

  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> targetBuildToolsVersion;
  private final Optional<String> targetNdkVersion;
  private final PropertyFinder propertyFinder;

  private final Supplier<Optional<Path>> sdkSupplier;
  private final Supplier<Path> buildToolsSupplier;
  private final Supplier<Optional<Path>> ndkSupplier;

  public DefaultAndroidDirectoryResolver(
      ProjectFilesystem projectFilesystem,
      Optional<String> targetBuildToolsVersion,
      Optional<String> targetNdkVersion,
      PropertyFinder propertyFinder) {
    this.projectFilesystem = projectFilesystem;
    this.targetBuildToolsVersion = targetBuildToolsVersion;
    this.targetNdkVersion = targetNdkVersion;
    this.propertyFinder = propertyFinder;

    this.sdkSupplier =
        Suppliers.memoize(new Supplier<Optional<Path>>() {
          @Override
          public Optional<Path> get() {
            return getSdkPathFromSdkDir();
          }
        });

    this.buildToolsSupplier =
        Suppliers.memoize(new Supplier<Path>() {
          @Override
          public Path get() {
            return getBuildToolsPathFromSdkDir();
          }
        });
    this.ndkSupplier =
        Suppliers.memoize(new Supplier<Optional<Path>>() {
          @Override
          public Optional<Path> get() {
            return getNdkPathFromNdkRepository().or(getNdkPathFromNdkDir());
          }
        });
  }

  @Override
  public Optional<Path> findAndroidSdkDirSafe() {
    return sdkSupplier.get();
  }

  @Override
  public Path findAndroidSdkDir() {
    Optional<Path> androidSdkDir = findAndroidSdkDirSafe();
    Preconditions.checkState(androidSdkDir.isPresent(),
        "Android SDK could not be found.  Set the environment variable ANDROID_SDK to point to " +
            "your Android SDK.");
    return androidSdkDir.get();
  }

  @Override
  public Path findAndroidBuildToolsDir() {
    return buildToolsSupplier.get();
  }

  @Override
  public Optional<Path> findAndroidNdkDir() {
    return ndkSupplier.get();
  }

  @Override
  public Optional<String> getNdkVersion() {
    Optional<Path> ndkPath = findAndroidNdkDir();
    if (!ndkPath.isPresent()) {
      return Optional.absent();
    }
    return findNdkVersionFromPath(ndkPath.get());
  }

  private Optional<String> findNdkVersionFromPath(Path ndkPath) {
    Path releaseVersion =  ndkPath.resolve("RELEASE.TXT");
    Optional<String> contents = projectFilesystem.readFirstLineFromFile(releaseVersion);

    if (contents.isPresent()) {
      StringTokenizer stringTokenizer = new StringTokenizer(contents.get());
      if (stringTokenizer.hasMoreTokens()) {
        return Optional.of(stringTokenizer.nextToken());
      }
    }
    return Optional.absent();
  }

  private Optional<Path> getSdkPathFromSdkDir() {
    Optional<Path> androidSdkDir =
        propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
            "sdk.dir",
            "ANDROID_SDK",
            "ANDROID_HOME");
    if (androidSdkDir.isPresent()) {
      Preconditions.checkArgument(androidSdkDir.get().toFile().isDirectory(),
          "The location of your Android SDK %s must be a directory",
          androidSdkDir.get());
    }
    return androidSdkDir;
  }

  private Path getBuildToolsPathFromSdkDir() {
    Path androidSdkDir = findAndroidSdkDir();
    Path buildToolsDir = androidSdkDir.resolve("build-tools");

    if (buildToolsDir.toFile().isDirectory()) {
      // In older versions of the ADT that have been upgraded via the SDK manager, the build-tools
      // directory appears to contain subfolders of the form "17.0.0". However, newer versions of
      // the ADT that are downloaded directly from http://developer.android.com/ appear to have
      // subfolders of the form android-4.2.2. We need to support both of these scenarios.
      File[] directories = buildToolsDir.toFile().listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          return pathname.isDirectory();
        }
      });

      if (directories.length == 0) {
        throw new HumanReadableException(
            Joiner.on(System.getProperty("line.separator")).join(
                "%s was empty, but should have contained a subdirectory with build tools.",
                "Install them using the Android SDK Manager (%s)."),
            buildToolsDir,
            androidSdkDir.resolve("tools").resolve("android"));
      } else {
        return pickCorrectBuildToolsDir(ImmutableSet.copyOf(directories));
      }

    }
    if (targetBuildToolsVersion.isPresent()) {
      // We were looking for a specific version, but we aren't going to find it at this point since
      // nothing under platform-tools was versioned.
      throw unableToFindTargetBuildTools();
    }
    // Build tools used to exist inside of platform-tools, so fallback to that.
    return androidSdkDir.resolve("platform-tools");
  }

  private Optional<Path> getNdkPathFromNdkDir() {
    Optional<Path> path = propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        "ndk.dir",
        "ANDROID_NDK",
        "NDK_HOME");

    if (path.isPresent()) {
      Path ndkPath = path.get();
      Optional<String> ndkVersionOptional = findNdkVersionFromPath(ndkPath);
      if (!ndkVersionOptional.isPresent()) {
        throw new HumanReadableException(
            "Failed to read NDK version from %s", ndkPath);
      } else {
        String ndkVersion = ndkVersionOptional.get();
        if (targetNdkVersion.isPresent() &&
            !isEquivalentToExpected(targetNdkVersion.get(), ndkVersion)) {
          throw new HumanReadableException(
              "Supported NDK version is %s but Buck is configured to use %s with " +
                  "ndk.dir or ANDROID_NDK",
              targetNdkVersion.get(),
              ndkVersion);
        }
      }
    }
    return path;
  }

  private Optional<Path> getNdkPathFromNdkRepository() {
    Optional<Path> repositoryPathOptional =
        propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
            "ndk.repository",
            "ANDROID_NDK_REPOSITORY");

    Optional<Path> path = Optional.absent();

    if (repositoryPathOptional.isPresent()) {
      Path repositoryPath = repositoryPathOptional.get();

      ImmutableSortedSet<Path> repositoryPathContents;
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(repositoryPath)) {
        repositoryPathContents = ImmutableSortedSet.copyOf(stream);
      } catch (IOException e) {
        throw new HumanReadableException(
            e,
            "Failed to read the Android NDK repository directory: %s",
            repositoryPath);
      }

      Optional<String> newestVersion = Optional.absent();
      VersionStringComparator versionComparator = new VersionStringComparator();
      for (Path potentialNdkPath : repositoryPathContents) {
        if (potentialNdkPath.toFile().isDirectory()) {
          Optional<String> ndkVersion = findNdkVersionFromPath(potentialNdkPath);
          // For each directory found, first check to see if it is in fact something we
          // believe to be a NDK directory.  If it is, check to see if we have a
          // target version and if this NDK directory matches it.  If not, choose the
          // newest version.
          //
          // It is possible to collapse this all into one if statement, but it is
          // significantly harder to grok.
          if (ndkVersion.isPresent()) {
            if (targetNdkVersion.isPresent()) {
              if (isEquivalentToExpected(targetNdkVersion.get(), ndkVersion.get())) {
                return Optional.of(potentialNdkPath);
              }
            } else {
              if (!newestVersion.isPresent() || versionComparator.compare(
                  ndkVersion.get(),
                  newestVersion.get()) > 0) {
                path = Optional.of(potentialNdkPath);
                newestVersion = Optional.of(ndkVersion.get());
              }
            }
          }
        }
      }
      if (!path.isPresent()) {
        throw new HumanReadableException(
            "Couldn't find a valid NDK under %s", repositoryPath);
      }
    }
    return path;
  }

  /**
   * If {@link #targetBuildToolsVersion} is present, returns that directory or throws a
   * {@link HumanReadableException}.  Otherwise, find the newest build-tools version.
   */
  private Path pickCorrectBuildToolsDir(Set<File> directories) {
    List<File> apiVersionDirectories = Lists.newArrayList();
    List<File> androidVersionDirectories = Lists.newArrayList();
    for (File dir : directories) {
      if (dir.getName().startsWith(ANDROID_VERSION_PREFIX)) {
        androidVersionDirectories.add(dir);
      } else {
        apiVersionDirectories.add(dir);
      }
    }

    final VersionStringComparator comparator = new VersionStringComparator();

    // API version directories are downloaded by the package manager, whereas Android version
    // directories are bundled with the SDK when it's unpacked. So API version directories will
    // presumably be newer.
    if (!apiVersionDirectories.isEmpty()) {
      if (targetBuildToolsVersion.isPresent()) {
        for (File dir : apiVersionDirectories) {
          if (dir.getName().equals(targetBuildToolsVersion.get())) {
            return dir.toPath();
          }
        }
        throw unableToFindTargetBuildTools();
      }
      Collections.sort(apiVersionDirectories, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
              String versionA = a.getName();
              String versionB = b.getName();
              return comparator.compare(versionA, versionB);
        }
      });
      // Return the last element in the list.
      return apiVersionDirectories.get(apiVersionDirectories.size() - 1).toPath();
    } else {
      Collections.sort(androidVersionDirectories, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
              String versionA = a.getName().substring(ANDROID_VERSION_PREFIX.length());
              String versionB = b.getName().substring(ANDROID_VERSION_PREFIX.length());
              return comparator.compare(versionA, versionB);
        }
      });
      // Return the last element in the list.
      return androidVersionDirectories.get(androidVersionDirectories.size() - 1).toPath();
    }
  }

  private HumanReadableException unableToFindTargetBuildTools() {
    throw new HumanReadableException(
        "Unable to find build-tools version %s, which is specified by your config.  Please see " +
            "https://buckbuild.com/concept/buckconfig.html#android.build_tools_version for more " +
            "details about the setting.  To install the correct version of the tools, run " +
            "`%s update sdk --force --no-ui --all --filter build-tools-%s`",
        targetBuildToolsVersion.get(),
        Escaper.escapeAsShellString(findAndroidSdkDir().resolve("tools/android").toString()),
        targetBuildToolsVersion.get());
  }

  private boolean isEquivalentToExpected(String expected, String candidate) {
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
        Objects.equals(findAndroidNdkDir(), that.findAndroidNdkDir());
  }

  @Override
  public String toString() {
    return String.format(
        "%s projectFilesystem=%s, targetBuildToolsVersion=%s, targetNdkVersion=%s, " +
            "propertyFinder=%s, findAndroidNdkDir()=%s",
        super.toString(),
        projectFilesystem,
        targetBuildToolsVersion,
        targetNdkVersion,
        propertyFinder,
        findAndroidNdkDir());
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
