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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PropertyFinder;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * Utility class used for resolving the location of Android specific directories.
 */
public class DefaultAndroidDirectoryResolver implements AndroidDirectoryResolver {
  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> targetNdkVersion;
  private final PropertyFinder propertyFinder;

  private final Supplier<Optional<Path>> sdkSupplier;
  private final Supplier<Optional<Path>> ndkSupplier;

  public DefaultAndroidDirectoryResolver(
      ProjectFilesystem projectFilesystem,
      Optional<String> targetNdkVersion,
      PropertyFinder propertyFinder) {
    this.projectFilesystem = projectFilesystem;
    this.targetNdkVersion = targetNdkVersion;
    this.propertyFinder = propertyFinder;

    this.sdkSupplier =
        Suppliers.memoize(new Supplier<Optional<Path>>() {
          @Override
          public Optional<Path> get() {
            return getSdkPathFromSdkDir();
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
        "Android SDK could not be find.  Set the environment variable ANDROID_SDK to point to " +
            "your Android SDK.");
    return androidSdkDir.get();
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
      return Optional.of(new StringTokenizer(contents.get()).nextToken());
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

  private Optional<Path> getNdkPathFromNdkDir() {
    Optional<Path> path = propertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        "ndk.dir",
        "ANDROID_NDK");

    if (path.isPresent()) {
      Path ndkPath = path.get();
      Optional<String> ndkVersionOptional = findNdkVersionFromPath(ndkPath);
      if (!ndkVersionOptional.isPresent()) {
        throw new HumanReadableException(
            "Failed to read NDK version from %s", ndkPath);
      } else {
        String ndkVersion = ndkVersionOptional.get();
        if (targetNdkVersion.isPresent() && !targetNdkVersion.get().equals(ndkVersion)) {
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

      String newestVersion = "";
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
              if (targetNdkVersion.get().equals(ndkVersion.get())) {
                return Optional.of(potentialNdkPath);
              }
            } else if (ndkVersion.get().compareTo(newestVersion) > 0) {
              path = Optional.of(potentialNdkPath);
              newestVersion = ndkVersion.get();
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
        Objects.equals(targetNdkVersion, that.targetNdkVersion) &&
        Objects.equals(propertyFinder, that.propertyFinder) &&
        Objects.equals(findAndroidNdkDir(), that.findAndroidNdkDir());
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectFilesystem, targetNdkVersion, propertyFinder);
  }
}
