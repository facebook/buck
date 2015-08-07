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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;

public class AndroidPlatformTargetTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public TemporaryFolder projectRoot = new TemporaryFolder();

  @Test
  public void testCreateFromDefaultDirectoryStructure() {
    String name = "Example Inc.:Google APIs:16";
    Path androidSdkDir = MorePathsForTests.rootRelativePath("home/android");
    String platformDirectoryPath = "platforms/android-16";
    Set<Path> additionalJarPaths = ImmutableSet.of();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());

    AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
        .createFromDefaultDirectoryStructure(
            name,
            androidDirectoryResolver,
            platformDirectoryPath,
            additionalJarPaths,
            /* aaptOverride */ Optional.<Path>absent());
    assertEquals(name, androidPlatformTarget.getName());
    assertEquals(
        ImmutableList.of(
            MorePathsForTests.rootRelativePath(
                "home/android/platforms/android-16/android.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(MorePathsForTests.rootRelativePath(
            "home/android/platforms/android-16/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(MorePathsForTests.rootRelativePath(
            "home/android/platforms/android-16/framework.aidl"),
        androidPlatformTarget.getAndroidFrameworkIdlFile());
    assertEquals(MorePathsForTests.rootRelativePath(
            "home/android/tools/proguard/lib/proguard.jar"),
        androidPlatformTarget.getProguardJar());
    assertEquals(MorePathsForTests.rootRelativePath(
            "home/android/tools/proguard/proguard-android.txt"),
        androidPlatformTarget.getProguardConfig());
    assertEquals(MorePathsForTests.rootRelativePath(
            "home/android/tools/proguard/proguard-android-optimize.txt"),
        androidPlatformTarget.getOptimizedProguardConfig());
    assertEquals(androidSdkDir.resolve("platform-tools/aapt").toAbsolutePath(),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(androidSdkDir.resolve("platform-tools/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(
        androidSdkDir.resolve(
            Platform.detect() == Platform.WINDOWS ? "platform-tools/dx.bat" : "platform-tools/dx"),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testInstalledNewerBuildToolsViaOldUpgradePath() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    File buildToolsDirFromOldUpgradePath = new File(buildToolsDir, "17.0.0");
    buildToolsDirFromOldUpgradePath.mkdir();
    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    String platformId = "Google Inc.:Google APIs:17";
    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            platformId,
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());

    assertTrue(androidPlatformTargetOption.isPresent());
    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/effects.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/maps.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/usb.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(pathToAndroidSdkDir.resolve("build-tools/17.0.0/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(pathToAndroidSdkDir.resolve("platform-tools/adb"),
        androidPlatformTarget.getAdbExecutable());
    assertEquals(pathToAndroidSdkDir.resolve("build-tools/17.0.0/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(pathToAndroidSdkDir.resolve(Platform.detect() == Platform.WINDOWS ?
                "build-tools/17.0.0/dx.bat" : "build-tools/17.0.0/dx"),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testInstalledNewerBuildToolsViaFreshDownload() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    File buildToolsDirFromFreshDownload = new File(buildToolsDir, "android-4.2.2");
    buildToolsDirFromFreshDownload.mkdir();
    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    String platformId = "Google Inc.:Google APIs:17";
    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            platformId,
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());

    assertTrue(androidPlatformTargetOption.isPresent());
    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/effects.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/maps.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/usb.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(pathToAndroidSdkDir.resolve("build-tools/android-4.2.2/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(pathToAndroidSdkDir.resolve("platform-tools/adb"),
        androidPlatformTarget.getAdbExecutable());
    assertEquals(pathToAndroidSdkDir.resolve("build-tools/android-4.2.2/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(pathToAndroidSdkDir.resolve(Platform.detect() == Platform.WINDOWS ?
                "build-tools/android-4.2.2/dx.bat" : "build-tools/android-4.2.2/dx"),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testChoosesCorrectBuildToolsDirectory() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    // Here is a number of subfolders in the build tools directory.
    new File(buildToolsDir, "android-4.2.2").mkdir();
    new File(buildToolsDir, "android-4.1").mkdir();
    new File(buildToolsDir, "android-4.0.0").mkdir();
    new File(buildToolsDir, "17.0.0").mkdir();
    new File(buildToolsDir, "16.0.0").mkdir();

    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            "Google Inc.:Google APIs:17",
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption.isPresent());

    assertEquals(
        "17.0.0 should be used as the build directory",
        new File(androidSdkDir, "build-tools/17.0.0/aapt").toPath().toAbsolutePath(),
        androidPlatformTargetOption.get().getAaptExecutable());
  }

  @Test
  public void testChoosesCorrectBuildToolsBinDirectory() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();

    // Here is a number of subfolders in the build tools directory.
    new File(buildToolsDir, "17.0.0").mkdir();
    File buildToolsDir23 = new File(buildToolsDir, "23.0.0_rc1");
    buildToolsDir23.mkdir();

    // Create the bin directory that is found inside newer SDK build-tools folders.
    new File(buildToolsDir23, "bin").mkdir();

    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            "android-17",
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption.isPresent());

    assertThat(
        "aapt should be found in the bin directory",
        androidPlatformTargetOption.get().getAaptExecutable(),
        equalTo(new File(
                androidSdkDir,
                "build-tools/23.0.0_rc1/bin/aapt").toPath().toAbsolutePath()));
    assertThat(
        "aidl should be found in the bin directory",
        androidPlatformTargetOption.get().getAidlExecutable(),
        equalTo(new File(
                androidSdkDir,
                "build-tools/23.0.0_rc1/bin/aidl").toPath().toAbsolutePath()));
    assertThat(
        "zipalign should be found in the bin directory",
        androidPlatformTargetOption.get().getZipalignExecutable(),
        equalTo(new File(
                androidSdkDir,
                "build-tools/23.0.0_rc1/bin/zipalign").toPath().toAbsolutePath()));
  }

  @Test
  public void testLooksForAdditionalAddonsDirectories() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    new File(buildToolsDir, "android-4.2.2").mkdir();

    File addOnsLibsDir1 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir1.mkdirs();
    File addOnsLibsDir2 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17-2/libs");
    addOnsLibsDir2.mkdirs();
    Files.touch(new File(addOnsLibsDir2, "effects.jar"));

    // '-11' to test that the sorting works correctly.
    File addOnsLibsDir3 = new File(androidSdkDir, "add-ons/addon-google_apis-google-17-11/libs");
    addOnsLibsDir3.mkdirs();
    Files.touch(new File(addOnsLibsDir3, "ignored.jar"));

    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            "Google Inc.:Google APIs:17",
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption.isPresent());

    // Verify that addOnsLibsDir2 was picked up since addOnsLibsDir1 is empty.
    assertTrue(androidPlatformTargetOption.get().getBootclasspathEntries().contains(
            addOnsLibsDir2.toPath().resolve("effects.jar").toAbsolutePath()));
    assertFalse(androidPlatformTargetOption.get().getBootclasspathEntries().contains(
            addOnsLibsDir3.toPath().resolve("ignored.jar").toAbsolutePath()));
  }

  @Test
  public void testLooksForOptionalLibraries() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
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
    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            platformId,
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());

    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
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
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    try {
      AndroidPlatformTarget.getTargetForId(
          platformId,
          androidDirectoryResolver,
          /* aaptOverride */ Optional.<Path>absent());
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals(
          String.format(
              "Google APIs not found in %s.\n" +
              "Please run '%s/tools/android sdk' and select both 'SDK Platform' and " +
              "'Google APIs' under Android (API 17)",
              new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs").getAbsolutePath(),
              androidSdkDir.getPath()),
          e.getMessage());
    }
  }

  @Test
  public void testLooksForGoogleLibsOnlyWhenGoogleApiTarget() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    new File(buildToolsDir, "android-4.2.2").mkdir();

    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));
    Files.touch(new File(addOnsLibsDir, "maps.jar"));
    Files.touch(new File(addOnsLibsDir, "usb.jar"));

    // This one should include the Google jars
    Optional<AndroidPlatformTarget> androidPlatformTargetOption1 =
        AndroidPlatformTarget.getTargetForId(
            "Google Inc.:Google APIs:17",
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption1.isPresent());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-17/android.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/effects.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/maps.jar"),
            pathToAndroidSdkDir.resolve("add-ons/addon-google_apis-google-17/libs/usb.jar")),
        androidPlatformTargetOption1.get().getBootclasspathEntries());

    // This one should only include android.jar
    Optional<AndroidPlatformTarget> androidPlatformTargetOption2 =
        AndroidPlatformTarget.getTargetForId(
            "android-17",
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption2.isPresent());
    assertEquals(
        ImmutableList.of(
            pathToAndroidSdkDir.resolve("platforms/android-17/android.jar")),
        androidPlatformTargetOption2.get().getBootclasspathEntries());
  }

  @Test
  public void testPlatformTargetFindsCorrectZipAlign() throws IOException {
    File androidSdkDir = tempDir.newFolder();
    Path pathToAndroidSdkDir = androidSdkDir.toPath();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir.toPath()),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();
    File buildToolsDirFromOldUpgradePath = new File(buildToolsDir, "17.0.0");
    buildToolsDirFromOldUpgradePath.mkdir();
    Files.touch(new File(buildToolsDirFromOldUpgradePath, "zipalign"));
    File addOnsLibsDir = new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs");
    addOnsLibsDir.mkdirs();
    Files.touch(new File(addOnsLibsDir, "effects.jar"));

    String platformId = "Google Inc.:Google APIs:17";
    Optional<AndroidPlatformTarget> androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            platformId,
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());

    assertTrue(androidPlatformTargetOption.isPresent());
    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
    assertEquals(
        pathToAndroidSdkDir.resolve("build-tools/17.0.0/zipalign"),
        androidPlatformTarget.getZipalignExecutable());

    File toolsDir = new File(androidSdkDir, "tools");
    toolsDir.mkdirs();
    Files.touch(new File(toolsDir, "zipalign"));
    androidPlatformTargetOption =
        AndroidPlatformTarget.getTargetForId(
            platformId,
            androidDirectoryResolver,
            /* aaptOverride */ Optional.<Path>absent());
    assertTrue(androidPlatformTargetOption.isPresent());
    androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
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
    Matcher matcher = AndroidPlatformTarget.PLATFORM_TARGET_PATTERN.matcher(input);
    assertEquals(matches, matcher.matches());
    if (matches) {
      assertEquals(id, matcher.group(1));
    }
  }
}
