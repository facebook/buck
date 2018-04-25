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

import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.toolchain.AndroidBuildToolsLocation;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.TestAndroidSdkLocationFactory;
import com.facebook.buck.android.toolchain.impl.AndroidBuildToolsResolver;
import com.facebook.buck.android.toolchain.impl.AndroidPlatformTargetProducer;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import java.nio.file.Paths;
import java.util.Optional;

public class AssumeAndroidPlatform {

  private AssumeAndroidPlatform() {}

  public static void assumeNdkIsAvailable() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);

    assumeTrue(androidNdk.isPresent());
  }

  public static void assumeSdkIsAvailable() throws InterruptedException {
    try {
      assumeNotNull(getAndroidSdkLocation().getSdkRootPath());
    } catch (HumanReadableException e) {
      assumeNoException(e);
    }
  }

  private static AndroidSdkLocation getAndroidSdkLocation() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    return TestAndroidSdkLocationFactory.create(projectFilesystem);
  }

  /**
   * Checks that Android SDK has build tools with aapt that supports `--output-test-symbols`.
   *
   * <p>It seems that this option appeared in build-tools 26.0.2 and the check only verifies the
   * version of build tools, it doesn't run aapt2 to verify it actually supports the option.
   */
  public static void assumeAapt2WithOutputTextSymbolsIsAvailable() throws InterruptedException {
    AndroidSdkLocation androidSdkLocation = getAndroidSdkLocation();

    assumeBuildToolsIsNewer(androidSdkLocation, "26.0.2");

    assumeAapt2IsAvailable(androidSdkLocation);
  }

  private static void assumeAapt2IsAvailable(AndroidSdkLocation androidSdkLocation) {
    AndroidBuildToolsResolver buildToolsResolver =
        new AndroidBuildToolsResolver(
            AndroidNdkHelper.DEFAULT_CONFIG,
            AndroidSdkLocation.of(androidSdkLocation.getSdkRootPath()));
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTargetProducer.getDefaultPlatformTarget(
            AndroidBuildToolsLocation.of(buildToolsResolver.getBuildToolsPath()),
            AndroidSdkLocation.of(androidSdkLocation.getSdkRootPath()),
            Optional.empty(),
            Optional.empty());

    assumeTrue(androidPlatformTarget.getAapt2Executable().toFile().exists());
  }

  /**
   * Checks that Android build tools have version that matches the provided or is newer.
   *
   * <p>Versions are expected to be in format like "25.0.2".
   */
  private static void assumeBuildToolsIsNewer(
      AndroidSdkLocation androidSdkLocation, String expectedBuildToolsVersion) {
    AndroidBuildToolsResolver buildToolsResolver =
        new AndroidBuildToolsResolver(
            AndroidNdkHelper.DEFAULT_CONFIG,
            AndroidSdkLocation.of(androidSdkLocation.getSdkRootPath()));
    Optional<String> sdkBuildToolsVersion = buildToolsResolver.getBuildToolsVersion();

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
