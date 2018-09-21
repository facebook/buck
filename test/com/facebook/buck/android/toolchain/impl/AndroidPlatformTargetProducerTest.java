/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.toolchain.AndroidBuildToolsLocation;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AndroidPlatformTargetProducerTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public TemporaryFolder projectRoot = new TemporaryFolder();
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot().toPath());
  }

  @Test
  public void testCreateFromDefaultDirectoryStructure() {
    String name = "Example Inc.:Google APIs:16";
    Path androidSdkDir = MorePathsForTests.rootRelativePath("home/android");
    String platformDirectoryPath = "platforms/android-16";
    Set<Path> additionalJarPaths = ImmutableSet.of();

    AndroidBuildToolsLocation buildToolsLocation =
        AndroidBuildToolsLocation.of(androidSdkDir.resolve("platform-tools"));
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTargetProducer.createFromDefaultDirectoryStructure(
            filesystem,
            name,
            buildToolsLocation,
            AndroidSdkLocation.of(androidSdkDir),
            platformDirectoryPath,
            additionalJarPaths,
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());
    assertEquals(name, androidPlatformTarget.getPlatformName());
    assertEquals(
        ImmutableList.of(
            MorePathsForTests.rootRelativePath("home/android/platforms/android-16/android.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(
        MorePathsForTests.rootRelativePath("home/android/platforms/android-16/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(
        MorePathsForTests.rootRelativePath("home/android/platforms/android-16/framework.aidl"),
        androidPlatformTarget.getAndroidFrameworkIdlFile());
    assertEquals(
        MorePathsForTests.rootRelativePath("home/android/tools/proguard/lib/proguard.jar"),
        androidPlatformTarget.getProguardJar());
    assertEquals(
        MorePathsForTests.rootRelativePath("home/android/tools/proguard/proguard-android.txt"),
        androidPlatformTarget.getProguardConfig());
    assertEquals(
        MorePathsForTests.rootRelativePath(
            "home/android/tools/proguard/proguard-android-optimize.txt"),
        androidPlatformTarget.getOptimizedProguardConfig());
    assertEquals(
        androidSdkDir.resolve("platform-tools/aapt").toAbsolutePath(),
        androidSdkDir.resolve(buildToolsLocation.getAaptPath()));
    assertEquals(
        androidSdkDir.resolve("platform-tools/aidl"), androidPlatformTarget.getAidlExecutable());
    assertEquals(
        androidSdkDir.resolve(
            Platform.detect() == Platform.WINDOWS ? "platform-tools/dx.bat" : "platform-tools/dx"),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testLooksForAdditionalAddonsDirectories() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    buildToolsDir = new File(buildToolsDir, "android-4.2.2");
    buildToolsDir.mkdir();

    File addOnsLibsDir1 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir1.mkdirs();
    File addOnsLibsDir2 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17-2/libs");
    addOnsLibsDir2.mkdirs();
    Files.touch(new File(addOnsLibsDir2, "effects.jar"));

    // '-11' to test that the sorting works correctly.
    File addOnsLibsDir3 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17-11/libs");
    addOnsLibsDir3.mkdirs();
    Files.touch(new File(addOnsLibsDir3, "ignored.jar"));

    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            "Google Inc.:Google APIs:17",
            AndroidBuildToolsLocation.of(buildToolsDir.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());

    // Verify that addOnsLibsDir2 was picked up since addOnsLibsDir1 is empty.
    assertTrue(
        androidPlatformTarget
            .getBootclasspathEntries()
            .contains(addOnsLibsDir2.toPath().resolve("effects.jar").toAbsolutePath()));
    assertFalse(
        androidPlatformTarget
            .getBootclasspathEntries()
            .contains(addOnsLibsDir3.toPath().resolve("ignored.jar").toAbsolutePath()));
  }

  @Test
  public void testLooksForOptionalLibraries() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    File buildToolsDir = new File(new File(androidSdkDir, "build-tools"), "23.0.1");
    buildToolsDir.mkdirs();
    File optionalLibsDir = new File(androidSdkDir, "platforms/android-23/optional");
    optionalLibsDir.mkdirs();
    Files.touch(new File(optionalLibsDir, "httpclient.jar"));
    Files.touch(new File(optionalLibsDir, "telemetry.jar"));

    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-23/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    String platformId = "Google Inc.:Google APIs:23";
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            platformId,
            AndroidBuildToolsLocation.of(buildToolsDir.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());

    assertEquals(platformId, androidPlatformTarget.getPlatformName());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-23/android.jar"),
            pathToAndroidSdkDir.resolve("platforms/android-23/optional/httpclient.jar"),
            pathToAndroidSdkDir.resolve("platforms/android-23/optional/telemetry.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-23/libs/effects.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-23/libs/maps.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-23/libs/usb.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(
        pathToAndroidSdkDir.resolve("platforms/android-23/android.jar"),
        androidPlatformTarget.getAndroidJar());
  }

  @Test
  public void testThrowsExceptionWhenAddOnsDirectoryIsMissing() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    String platformId = "Google Inc.:Google APIs:17";
    try {
      AndroidPlatformTargetProducer.getTargetForId(
          filesystem,
          platformId,
          AndroidBuildToolsLocation.of(androidSdkDir.toPath().resolve("build-tools")),
          AndroidSdkLocation.of(androidSdkDir.toPath()),
          /* aaptOverride */ Optional.empty(),
          /* aapt2Override */ Optional.empty());
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals(
          String.format(
              "Google APIs not found in %s.\n"
                  + "Please run '%s/tools/android sdk' and select both 'SDK Platform' and "
                  + "'Google APIs' under Android (API 17)",
              new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs").getAbsolutePath(),
              androidSdkDir.getPath()),
          e.getMessage());
    }
  }

  @Test
  public void testLooksForGoogleLibsOnlyWhenGoogleApiTarget() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    buildToolsDir = new File(buildToolsDir, "android-4.2.2");
    buildToolsDir.mkdir();

    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    // This one should include the Google jars
    AndroidPlatformTarget androidPlatformTarget1 =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            "Google Inc.:Google APIs:17",
            AndroidBuildToolsLocation.of(buildToolsDir.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/effects.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/maps.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/usb.jar")),
        androidPlatformTarget1.getBootclasspathEntries());

    // This one should only include android.jar
    AndroidPlatformTarget androidPlatformTarget2 =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            "android-17",
            AndroidBuildToolsLocation.of(buildToolsDir.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());
    assertEquals(
        ImmutableList.of(pathToAndroidSdkDir.resolve("platforms/android-17/android.jar")),
        androidPlatformTarget2.getBootclasspathEntries());
  }

  @Test
  public void testPlatformTargetFindsCorrectZipAlign() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    File buildToolsDirFromOldUpgradePath =
        new File(new File(androidSdkDir, "build-tools"), "17.0.0");
    buildToolsDirFromOldUpgradePath.mkdirs();
    Files.touch(new File(buildToolsDirFromOldUpgradePath, "zipalign"));
    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));

    String platformId = "Google Inc.:Google APIs:17";
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            platformId,
            AndroidBuildToolsLocation.of(buildToolsDirFromOldUpgradePath.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());

    assertEquals(platformId, androidPlatformTarget.getPlatformName());
    assertEquals(
        pathToAndroidSdkDir.resolve("build-tools/17.0.0/zipalign"),
        androidPlatformTarget.getZipalignExecutable());

    File toolsDir = new File(androidSdkDir, "tools");
    toolsDir.mkdirs();
    Files.touch(new File(toolsDir, "zipalign"));
    androidPlatformTarget =
        AndroidPlatformTargetProducer.getTargetForId(
            filesystem,
            platformId,
            AndroidBuildToolsLocation.of(buildToolsDirFromOldUpgradePath.toPath()),
            AndroidSdkLocation.of(androidSdkDir.toPath()),
            /* aaptOverride */ Optional.empty(),
            /* aapt2Override */ Optional.empty());
    assertEquals(platformId, androidPlatformTarget.getPlatformName());
    assertEquals(
        pathToAndroidSdkDir.resolve("tools/zipalign"),
        androidPlatformTarget.getZipalignExecutable());
  }

  @Test
  public void testPlatformTargetPattern() {
    testPlatformTargetRegex("Google Inc.:Google APIs:8", true, "8");
    testPlatformTargetRegex("Google Inc.:Google APIs:17", true, "17");
    testPlatformTargetRegex("Google Inc.:Google APIs:MNC", true, "MNC");
    testPlatformTargetRegex("android-8", true, "8");
    testPlatformTargetRegex("android-17", true, "17");
    testPlatformTargetRegex("android-MNC", true, "MNC");
    testPlatformTargetRegex("Google Inc.:Google APIs:", false, "");
    testPlatformTargetRegex("android-", false, "");
  }

  private void testPlatformTargetRegex(String input, boolean matches, String id) {
    Matcher matcher = AndroidPlatformTargetProducer.PLATFORM_TARGET_PATTERN.matcher(input);
    assertEquals(matches, matcher.matches());
    if (matches) {
      assertEquals(id, matcher.group(1));
    }
  }
}
