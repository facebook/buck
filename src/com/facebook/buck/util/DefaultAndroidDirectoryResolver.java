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
package com.facebook.buck.util;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.util.StringTokenizer;

/**
 * Utility class used for resolving the location of Android specific directories.
 */
public class DefaultAndroidDirectoryResolver implements AndroidDirectoryResolver {
  private final ProjectFilesystem projectFilesystem;

  public DefaultAndroidDirectoryResolver(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public Optional<Path> findAndroidSdkDirSafe() {
    Optional<Path> androidSdkDir = PropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        projectFilesystem,
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
    Optional<Path> path =
        PropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
            projectFilesystem,
            "ndk.dir",
            "ANDROID_NDK");
    return path;
  }

  /**
   * @return The NDK version being used to build, parsed from RELEASE.TXT in the NDK directory.
   */
  @Override
  public String getNdkVersion(Path ndkPath) {
    Path releaseVersion =  ndkPath.resolve("RELEASE.TXT");
    Optional<String> contents = projectFilesystem.readFirstLineFromFile(releaseVersion);
    String version;

    if (contents.isPresent()) {
      version = new StringTokenizer(contents.get()).nextToken();
    } else {
      throw new HumanReadableException(
          "Failed to read NDK version from %s", releaseVersion);
    }
    return version;
  }
}
