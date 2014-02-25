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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.nio.file.Paths;
import java.util.Set;

public class AndroidPlatformTargetTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public TemporaryFolder projectRoot = new TemporaryFolder();

  @Test
  public void testCreateFromDefaultDirectoryStructure() {
    String name = "Example Inc.:Google APIs:16";
    Path androidSdkDir = Paths.get("/home/android");
    String platformDirectoryPath = "platforms/android-16";
    Set<String> additionalJarPaths = ImmutableSet.of();
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(androidSdkDir),
            /* androidNdkDir */ Optional.<Path>absent(),
            /* ndkVersion */ Optional.<String>absent());

    AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
        .createFromDefaultDirectoryStructure(
            name, androidDirectoryResolver, platformDirectoryPath, additionalJarPaths);
    assertEquals(name, androidPlatformTarget.getName());
    assertEquals(ImmutableList.of(Paths.get("/home/android/platforms/android-16/android.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(new File("/home/android/platforms/android-16/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(new File("/home/android/platforms/android-16/framework.aidl"),
        androidPlatformTarget.getAndroidFrameworkIdlFile());
    assertEquals(new File("/home/android/tools/proguard/lib/proguard.jar"),
        androidPlatformTarget.getProguardJar());
    assertEquals(new File("/home/android/tools/proguard/proguard-android.txt"),
        androidPlatformTarget.getProguardConfig());
    assertEquals(new File("/home/android/tools/proguard/proguard-android-optimize.txt"),
        androidPlatformTarget.getOptimizedProguardConfig());
    assertEquals(androidSdkDir.resolve("platform-tools/aapt").toFile(),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(androidSdkDir.resolve("platform-tools/aidl").toFile(),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(androidSdkDir.resolve("platform-tools/dx").toFile(),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testInstalledNewerBuildToolsViaOldUpgradePath() throws IOException {
    File androidSdkDir = tempDir.newFolder();
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
        AndroidPlatformTarget.getTargetForId(platformId, androidDirectoryResolver);

    assertTrue(androidPlatformTargetOption.isPresent());
    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
    assertEquals(
        ImmutableList.of(
            MorePaths.newPathInstance(new File(androidSdkDir, "platforms/android-17/android.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/effects.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/maps.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/usb.jar"))),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(new File(androidSdkDir, "platforms/android-17/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(new File(androidSdkDir, "platform-tools/adb"),
        androidPlatformTarget.getAdbExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/dx"),
        androidPlatformTarget.getDxExecutable());
    assertEquals(new File(androidSdkDir, "tools/zipalign"),
        androidPlatformTarget.getZipalignExecutable());
  }

  @Test
  public void testInstalledNewerBuildToolsViaFreshDownload() throws IOException {
    File androidSdkDir = tempDir.newFolder();
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
        AndroidPlatformTarget.getTargetForId(platformId, androidDirectoryResolver);

    assertTrue(androidPlatformTargetOption.isPresent());
    AndroidPlatformTarget androidPlatformTarget = androidPlatformTargetOption.get();
    assertEquals(platformId, androidPlatformTarget.getName());
    assertEquals(
        ImmutableList.of(
            MorePaths.newPathInstance(new File(androidSdkDir, "platforms/android-17/android.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/effects.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/maps.jar")),
            MorePaths.newPathInstance(
                new File(androidSdkDir, "add-ons/addon-google_apis-google-17/libs/usb.jar"))),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(new File(androidSdkDir, "platforms/android-17/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(new File(androidSdkDir, "build-tools/android-4.2.2/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(new File(androidSdkDir, "platform-tools/adb"),
        androidPlatformTarget.getAdbExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/android-4.2.2/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/android-4.2.2/dx"),
        androidPlatformTarget.getDxExecutable());
    assertEquals(new File(androidSdkDir, "tools/zipalign"),
        androidPlatformTarget.getZipalignExecutable());
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
            androidDirectoryResolver);
    assertTrue(androidPlatformTargetOption.isPresent());

    assertEquals(
        "android-4.2.2 should be used as the build directory",
        new File(androidSdkDir, "build-tools/android-4.2.2/aapt"),
        androidPlatformTargetOption.get().getAaptExecutable());
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
      AndroidPlatformTarget.getTargetForId(platformId, androidDirectoryResolver);
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
}
