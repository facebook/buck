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
package com.facebook.buck.android;

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.Optional;

public class AssumeAndroidPlatform {

  private AssumeAndroidPlatform() {}

  public static void assumeNdkIsAvailable() throws InterruptedException {
    assumeNotNull(getAndroidDirectoryResolver().getNdkOrAbsent().orElse(null));
  }

  public static void assumeSdkIsAvailable() throws InterruptedException {
    assumeNotNull(getAndroidDirectoryResolver().getSdkOrAbsent().orElse(null));
  }

  private static DefaultAndroidDirectoryResolver getAndroidDirectoryResolver()
      throws InterruptedException {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    return new DefaultAndroidDirectoryResolver(
        projectFilesystem.getRootPath().getFileSystem(),
        ImmutableMap.copyOf(System.getenv()),
        AndroidNdkHelper.DEFAULT_CONFIG);
  }

  /**
   * Checks that Android SDK has build tools with aapt that supports `--output-test-symbols`.
   *
   * <p>It seems that this option appeared in build-tools 26.0.2 and the check only verifies the
   * version of build tools, it doesn't run aapt2 to verify it actually supports the option.
   */
  public static void assumeAapt2WithOutputTextSymbolsIsAvailable() throws InterruptedException {
    DefaultAndroidDirectoryResolver androidDirectoryResolver = getAndroidDirectoryResolver();

    assumeBuildToolsIsNewer(androidDirectoryResolver, "26.0.2");

    assumeAapt2IsAvailable(androidDirectoryResolver);
  }

  private static void assumeAapt2IsAvailable(AndroidDirectoryResolver androidDirectoryResolver)
      throws InterruptedException {
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.getDefaultPlatformTarget(
            androidDirectoryResolver, Optional.empty(), Optional.empty());

    assumeTrue(androidPlatformTarget.getAapt2Executable().toFile().exists());
  }

  /**
   * Checks that Android build tools have version that matches the provided or is newer.
   *
   * <p>Versions are expected to be in format like "25.0.2".
   */
  private static void assumeBuildToolsIsNewer(
      DefaultAndroidDirectoryResolver androidDirectoryResolver, String expectedBuildToolsVersion) {
    Optional<String> sdkBuildToolsVersion = androidDirectoryResolver.getBuildToolsVersion();

    assumeTrue(sdkBuildToolsVersion.isPresent());

    assumeVersionIsNewer(
        sdkBuildToolsVersion.get(),
        expectedBuildToolsVersion,
        "Version "
            + sdkBuildToolsVersion.get()
            + " is less then requested version "
            + expectedBuildToolsVersion);
  }

  private static void assumeVersionIsNewer(
      String actualVersion, String expectedVersion, String message) {
    String[] actualVersionParts = actualVersion.split("\\.");
    String[] expectedVersionParts = expectedVersion.split("\\.");

    int currentPart = 0;
    while (currentPart < actualVersionParts.length || currentPart < expectedVersionParts.length) {
      int actualVersionPart =
          currentPart < actualVersionParts.length
              ? Integer.parseInt(actualVersionParts[currentPart])
              : 0;
      int expectedVersionPart =
          currentPart < expectedVersionParts.length
              ? Integer.parseInt(expectedVersionParts[currentPart])
              : 0;

      assumeTrue(message, expectedVersionPart <= actualVersionPart);

      currentPart++;
    }
  }
}
