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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class AndroidPlatformTargetTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testCreateFromDefaultDirectoryStructure() {
    String name = "Example Inc.:Google APIs:16";
    File androidSdkDir = new File("/home/android");
    String platformDirectoryPath = "platforms/android-16";
    Set<String> additionalJarPaths = ImmutableSet.of();
    AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
        .createFromDefaultDirectoryStructure(
            name, androidSdkDir, platformDirectoryPath, additionalJarPaths);
    assertEquals(name, androidPlatformTarget.getName());
    assertEquals(ImmutableList.of(new File("/home/android/platforms/android-16/android.jar")),
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
    assertEquals(new File(androidSdkDir, "platform-tools/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(new File(androidSdkDir, "platform-tools/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(new File(androidSdkDir, "platform-tools/dx"),
        androidPlatformTarget.getDxExecutable());
  }

  @Test
  public void testInstalledNewerBuildTools() throws IOException {
    String name = "Example Inc.:Google APIs:16";
    File androidSdkDir = tempDir.newFolder();
    File buildToolsDir = new File(androidSdkDir, "build-tools");
    buildToolsDir.mkdir();

    String platformDirectoryPath = "platforms/android-16";
    Set<String> additionalJarPaths = ImmutableSet.of();
    AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
        .createFromDefaultDirectoryStructure(
            name, androidSdkDir, platformDirectoryPath, additionalJarPaths);
    assertEquals(name, androidPlatformTarget.getName());
    assertEquals(ImmutableList.of(new File(androidSdkDir, "platforms/android-16/android.jar")),
        androidPlatformTarget.getBootclasspathEntries());
    assertEquals(new File(androidSdkDir, "platforms/android-16/android.jar"),
        androidPlatformTarget.getAndroidJar());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/aapt"),
        androidPlatformTarget.getAaptExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/aidl"),
        androidPlatformTarget.getAidlExecutable());
    assertEquals(new File(androidSdkDir, "build-tools/17.0.0/dx"),
        androidPlatformTarget.getDxExecutable());
  }
}
