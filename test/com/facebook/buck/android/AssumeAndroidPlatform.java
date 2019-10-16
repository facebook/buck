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

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.toolchain.AndroidBuildToolsLocation;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.TestAndroidSdkLocationFactory;
import com.facebook.buck.android.toolchain.impl.AndroidBuildToolsResolver;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.VersionStringComparator;
import java.nio.file.Paths;
import java.util.Optional;

public class AssumeAndroidPlatform {

  private static final VersionStringComparator VERSION_STRING_COMPARATOR =
      new VersionStringComparator();

  private AssumeAndroidPlatform() {}

  public static void assumeNdkIsAvailable() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    assumeTrue(androidNdk.isPresent());
  }

  public static void assumeArmIsAvailable() {
    assumeTrue(isArmAvailable());
  }

  public static boolean isArmAvailable() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    if (!androidNdk.isPresent()) {
      return false;
    }
    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "17") < 0;
  }

  public static void assumeGnuStlIsAvailable() {
    assumeTrue(isGnuStlAvailable());
  }

  public static void assumeGnuStlIsNotAvailable() {
    assumeFalse(isGnuStlAvailable());
  }

  public static boolean isGnuStlAvailable() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    if (!androidNdk.isPresent()) {
      return false;
    }

    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "18") < 0;
  }

  public static void assumeUnifiedHeadersAvailable() {
    assumeTrue(isUnifiedHeadersAvailable());
  }

  public static boolean isUnifiedHeadersAvailable() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    assumeTrue(androidNdk.isPresent());
    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "14") >= 0;
  }

  public static void assumeSdkIsAvailable() {
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
   * Checks that Android SDK has build tools with aapt2 that supports `--output-test-symbols`.
   *
   * <p>It seems that this option appeared in build-tools 26.0.2 and the check only verifies the
   * version of build tools, it doesn't run aapt2 to verify it actually supports the option.
   */
  public static void assumeAapt2WithOutputTextSymbolsIsAvailable() {
    assumeBuildToolsVersionIsAtLeast("26.0.2");
    assumeAapt2IsAvailable();
  }

  private static void assumeAapt2IsAvailable() {
    AndroidSdkLocation androidSdkLocation = getAndroidSdkLocation();
    AndroidBuildToolsResolver buildToolsResolver =
        new AndroidBuildToolsResolver(
            AndroidNdkHelper.DEFAULT_CONFIG,
            AndroidSdkLocation.of(androidSdkLocation.getSdkRootPath()));
    AndroidBuildToolsLocation toolsLocation =
        AndroidBuildToolsLocation.of(buildToolsResolver.getBuildToolsPath());
    // AndroidPlatformTarget ensures that aapt2 exists when getting the Tool.
    assumeTrue(
        androidSdkLocation
            .getSdkRootPath()
            .resolve(toolsLocation.getAapt2Path())
            .toFile()
            .exists());
  }

  /**
   * Checks that the Android build tools version is newer than or equal to the given version.
   *
   * @param expectedVersion a build tools version in a valid format, e.g. "25.0.2".
   */
  public static void assumeBuildToolsVersionIsAtLeast(String expectedVersion) {
    AndroidBuildToolsResolver buildToolsResolver =
        new AndroidBuildToolsResolver(
            AndroidNdkHelper.DEFAULT_CONFIG,
            AndroidSdkLocation.of(getAndroidSdkLocation().getSdkRootPath()));
    Optional<String> actualVersion = buildToolsResolver.getBuildToolsVersion();

    assumeTrue(actualVersion.isPresent());
    assumeTrue(
        "Build tools version "
            + actualVersion.get()
            + " is less than expected version "
            + expectedVersion,
        VERSION_STRING_COMPARATOR.compare(actualVersion.get(), expectedVersion) >= 0);
  }

  public static void assumeBundleBuildIsSupported() {
    assumeBuildToolsVersionIsAtLeast("28");
    assumeAapt2IsAvailable();
  }
}
