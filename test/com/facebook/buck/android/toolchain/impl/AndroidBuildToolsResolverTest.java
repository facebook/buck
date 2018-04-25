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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.FakeAndroidBuckConfig;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidBuildToolsResolverTest {

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private AndroidBuckConfig androidBuckConfig;

  @Before
  public void setUp() throws Exception {
    androidBuckConfig = new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect());
  }

  @Test
  public void buildToolsFallsBacktoPlatformTools() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "platform-tools");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));
    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("platform-tools"));
  }

  @Test
  public void buildToolsAPIVersionFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/android-4.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("android-4.2.2"));
  }

  @Test
  public void buildToolsWithBuildToolsPrefix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/build-tools-17.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(
        resolver.getBuildToolsPath().getFileName().toString(), equalTo("build-tools-17.2.2"));
  }

  @Test
  public void buildToolsInvalidPrefixThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/foobar-17.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("foobar-17.2.2");
    resolver.getBuildToolsPath();
  }

  @Test
  public void buildToolsEmptyDirectoryThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    sdkDir.resolve("build-tools").toFile().mkdir();
    sdkDir.resolve("tools").toFile().mkdir();
    Path toolsDir = sdkDir.resolve("tools").toAbsolutePath();
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "null was empty, but should have contained a subdirectory "
            + "with build tools. Install them using the Android SDK Manager ("
            + toolsDir
            + File.separator
            + "android).");
    resolver.getBuildToolsPath();
  }

  @Test
  public void buildToolsRCVersionsFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/23.0.0_rc1");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("23.0.0_rc1"));
  }

  @Test
  public void buildToolsRCAndNonRCMix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/22.0.0", "build-tools/23.0.0_rc1");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("23.0.0_rc1"));
  }

  @Test
  public void preferBuildToolsVersionedFoldersOverAPIFolders() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir,
        "build-tools/android-4.2.2",
        "build-tools/android-4.1",
        "build-tools/android-4.0.0",
        "build-tools/build-tools-15.0.0",
        "build-tools/17.0.0",
        "build-tools/16.0.0");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionIsSelected() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir, "build-tools/16.0.0", "build-tools/17.0.0", "build-tools/18.0.0");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(
            FakeAndroidBuckConfig.builder().setBuildToolsVersion("17.0.0").build(),
            AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionNotAvailableThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/18.0.0");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(
            FakeAndroidBuckConfig.builder().setBuildToolsVersion("2.0.0").build(),
            AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("2.0.0");
    resolver.getBuildToolsPath();
  }

  private void createBuildToolsVersions(Path sdkDir, String... directoryNames) {
    for (int i = 0; i < directoryNames.length; i++) {
      File folder = sdkDir.resolve(directoryNames[i]).toFile();
      assertThat(folder.mkdirs(), Matchers.is(true));
    }
  }
}
