/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.toolchain.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.FakeAndroidBuckConfig;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import java.io.File;
import java.io.IOException;
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
  public void setUp() {
    androidBuckConfig = new AndroidBuckConfig(FakeBuckConfig.empty(), Platform.detect());
  }

  @Test
  public void buildToolsFallsBacktoPlatformTools() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "platform-tools");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));
    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("platform-tools"));
  }

  @Test
  public void buildToolsAPIVersionFound() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/android-4.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("android-4.2.2"));
  }

  @Test
  public void buildToolsWithBuildToolsPrefix() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/build-tools-17.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(
        resolver.getBuildToolsPath().getFileName().toString(), equalTo("build-tools-17.2.2"));
  }

  @Test
  public void buildToolsInvalidPrefixThrows() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/foobar-17.2.2");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("foobar-17.2.2");
    resolver.getBuildToolsPath();
  }

  @Test
  public void buildToolsEmptyDirectoryThrows() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    sdkDir.resolve("build-tools").toFile().mkdir();
    sdkDir.resolve("tools").toFile().mkdir();
    AbsPath toolsDir = sdkDir.resolve("tools");
    AbsPath buildToolsDir = sdkDir.resolve("build-tools");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        buildToolsDir
            + " was empty, but should have contained a subdirectory "
            + "with build tools. Install them using the Android SDK Manager ("
            + toolsDir
            + File.separator
            + "android).");
    resolver.getBuildToolsPath();
  }

  @Test
  public void buildToolsRCVersionsFound() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/23.0.0_rc1");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("23.0.0_rc1"));
  }

  @Test
  public void buildToolsRCAndNonRCMix() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/22.0.0", "build-tools/23.0.0_rc1");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(androidBuckConfig, AndroidSdkLocation.of(sdkDir));

    assertThat(resolver.getBuildToolsPath().getFileName().toString(), equalTo("23.0.0_rc1"));
  }

  @Test
  public void preferBuildToolsVersionedFoldersOverAPIFolders() throws IOException {
    AbsPath sdkDir = tmpDir.newFolder("sdk");
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
    AbsPath sdkDir = tmpDir.newFolder("sdk");
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
    AbsPath sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/18.0.0");
    AndroidBuildToolsResolver resolver =
        new AndroidBuildToolsResolver(
            FakeAndroidBuckConfig.builder().setBuildToolsVersion("2.0.0").build(),
            AndroidSdkLocation.of(sdkDir));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("2.0.0");
    resolver.getBuildToolsPath();
  }

  private void createBuildToolsVersions(AbsPath sdkDir, String... directoryNames) {
    for (String directoryName : directoryNames) {
      File folder = sdkDir.resolve(directoryName).toFile();
      assertThat(folder.mkdirs(), Matchers.is(true));
    }
  }
}
