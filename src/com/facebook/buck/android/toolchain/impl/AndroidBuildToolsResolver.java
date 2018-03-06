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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class AndroidBuildToolsResolver {

  public static final ImmutableSet<String> BUILD_TOOL_PREFIXES =
      ImmutableSet.of("android-", "build-tools-");

  private final Optional<Path> buildTools;
  private final Optional<String> targetBuildToolsVersion;

  private Optional<String> buildToolsErrorMessage = Optional.empty();
  private Optional<String> discoveredBuildToolsVersion = Optional.empty();

  public AndroidBuildToolsResolver(
      AndroidBuckConfig config, AndroidSdkLocation androidSdkLocation) {
    this.targetBuildToolsVersion = config.getBuildToolsVersion();
    this.buildTools = findBuildTools(androidSdkLocation.getSdkRootPath());
  }

  public Path getBuildToolsPath() {
    if (!buildTools.isPresent() && buildToolsErrorMessage.isPresent()) {
      throw new HumanReadableException(buildToolsErrorMessage.get());
    }
    return buildTools.get();
  }

  /**
   * Returns Android SDK build tools version that was either discovered or provided during creation.
   */
  @VisibleForTesting
  public Optional<String> getBuildToolsVersion() {
    return discoveredBuildToolsVersion.isPresent()
        ? discoveredBuildToolsVersion
        : targetBuildToolsVersion;
  }

  private Optional<Path> findBuildTools(Path sdkPath) {
    Path toolsDir = sdkPath.resolve("build-tools");

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
          buildToolsErrorMessage = unableToFindTargetBuildTools(sdkPath);
          return Optional.empty();
        } else {
          return Optional.of(directories[0].toPath());
        }
      }

      // We aren't looking for a specific version, so we pick the newest version
      VersionStringComparator comparator = new VersionStringComparator();
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
      discoveredBuildToolsVersion = Optional.of(newestBuildDirVersion);
      return Optional.of(newestBuildDir.toPath());
    }
    if (targetBuildToolsVersion.isPresent()) {
      // We were looking for a specific version, but we aren't going to find it at this point since
      // nothing under platform-tools was versioned.
      buildToolsErrorMessage = unableToFindTargetBuildTools(sdkPath);
      return Optional.empty();
    }
    // Build tools used to exist inside of platform-tools, so fallback to that.
    return Optional.of(sdkPath.resolve("platform-tools"));
  }

  private static String stripBuildToolsPrefix(String name) {
    for (String prefix : BUILD_TOOL_PREFIXES) {
      if (name.startsWith(prefix)) {
        return name.substring(prefix.length());
      }
    }
    return name;
  }

  private Optional<String> unableToFindTargetBuildTools(Path sdkPath) {
    return Optional.of(
        "Unable to find build-tools version "
            + targetBuildToolsVersion.get()
            + ", which is specified by your config.  Please see "
            + "https://buckbuild.com/concept/buckconfig.html#android.build_tools_version for more "
            + "details about the setting.  To install the correct version of the tools, run `"
            + Escaper.escapeAsShellString(sdkPath.resolve("tools/bin/sdkmanager").toString())
            + " \"build-tools;"
            + targetBuildToolsVersion.get()
            + "\"`");
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof AndroidBuildToolsResolver)) {
      return false;
    }

    AndroidBuildToolsResolver that = (AndroidBuildToolsResolver) other;

    return Objects.equals(targetBuildToolsVersion, that.targetBuildToolsVersion);
  }

  @Override
  public String toString() {
    return String.format(
        "%s targetBuildToolsVersion=%s, AndroidBuildToolsDir=%s",
        super.toString(),
        targetBuildToolsVersion,
        (buildTools.isPresent()) ? (buildTools.get()) : "Build tools not available");
  }

  @Override
  public int hashCode() {
    return Objects.hash(targetBuildToolsVersion);
  }
}
