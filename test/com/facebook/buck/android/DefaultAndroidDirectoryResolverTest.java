/*
 * Copyright 2013-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultAndroidDirectoryResolverTest {

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getAbsentSdk() {
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(Optional.empty(), resolver.getSdkOrAbsent());
  }

  @Test
  public void throwAtAbsentSdk() {
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.SDK_NOT_FOUND_MESSAGE);
    resolver.getSdkOrThrow();
  }

  @Test
  public void throwAtAbsentBuildTools() {
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.TOOLS_NEED_SDK_MESSAGE);
    resolver.getBuildToolsOrThrow();
  }

  @Test
  public void throwAtSdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", file.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ANDROID_SDK",
            file));
    resolver.getSdkOrThrow();
  }

  @Test
  public void buildToolsFallsBacktoPlatformTools() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "platform-tools");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);
    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("platform-tools"));
  }

  @Test
  public void buildToolsAPIVersionFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/android-4.2.2");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("android-4.2.2"));
  }

  @Test
  public void buildToolsWithBuildToolsPrefix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/build-tools-17.2.2");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertThat(
        resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("build-tools-17.2.2"));
  }

  @Test
  public void buildToolsInvalidPrefixThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/foobar-17.2.2");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("foobar-17.2.2");
    resolver.getBuildToolsOrThrow();
  }

  @Test
  public void buildToolsEmptyDirectoryThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    sdkDir.resolve("build-tools").toFile().mkdir();
    sdkDir.resolve("tools").toFile().mkdir();
    Path toolsDir = sdkDir.resolve("tools").toAbsolutePath();
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "null was empty, but should have contained a subdirectory "
            + "with build tools. Install them using the Android SDK Manager ("
            + toolsDir
            + File.separator
            + "android).");
    resolver.getBuildToolsOrThrow();
  }

  @Test
  public void buildToolsRCVersionsFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/23.0.0_rc1");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("23.0.0_rc1"));
  }

  @Test
  public void buildToolsRCAndNonRCMix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/22.0.0", "build-tools/23.0.0_rc1");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("23.0.0_rc1"));
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
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionIsSelected() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir, "build-tools/16.0.0", "build-tools/17.0.0", "build-tools/18.0.0");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            FakeAndroidBuckConfig.builder().setBuildToolsVersion("17.0.0").build());

    assertThat(resolver.getBuildToolsOrThrow().getFileName().toString(), equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionNotAvailableThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/18.0.0");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.toString()),
            FakeAndroidBuckConfig.builder().setBuildToolsVersion("2.0.0").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("2.0.0");
    resolver.getBuildToolsOrThrow();
  }

  @Test
  public void testGetSdkFromBuckconfig() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder().setSdkPath(sdkDir.toString()).build());

    assertEquals(sdkDir, resolver.getSdkOrThrow());
  }

  @Test
  public void testSdkFromEnvironmentSupercedesBuckconfig() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_SDK", sdkDir.resolve("also-wrong").toString()),
            FakeAndroidBuckConfig.builder().setSdkPath(sdkDir.toString()).build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ANDROID_SDK",
            sdkDir.resolve("also-wrong")));
    resolver.getSdkOrThrow();
  }

  private Path[] createBuildToolsVersions(Path sdkDir, String... directoryNames)
      throws IOException {
    Path[] ret = new Path[directoryNames.length];
    for (int i = 0; i < directoryNames.length; i++) {
      File folder = sdkDir.resolve(directoryNames[i]).toFile();
      assertThat(folder.mkdirs(), Matchers.is(true));
      ret[i] = folder.toPath();
    }
    return ret;
  }
}
