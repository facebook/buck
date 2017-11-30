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

import static com.facebook.buck.android.DefaultAndroidDirectoryResolver.NDK_POST_R11_VERSION_FILENAME;
import static com.facebook.buck.android.DefaultAndroidDirectoryResolver.NDK_PRE_R11_VERSION_FILENAME;
import static com.facebook.buck.android.DefaultAndroidDirectoryResolver.NDK_TARGET_VERSION_IS_EMPTY_MESSAGE;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
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
  public void getAbsentSdkNdk() {
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(Optional.empty(), resolver.getSdkOrAbsent());
    assertEquals(Optional.empty(), resolver.getNdkOrAbsent());
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
  public void throwAtAbsentNdk() {
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.NDK_NOT_FOUND_MESSAGE);
    resolver.getNdkOrThrow();
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
  public void throwAtNdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", file.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ANDROID_NDK",
            file));
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtGetNdkDirectoryIsEmpty() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        ndkDir.toAbsolutePath()
            + " does not contain a valid "
            + "properties file for Android NDK.");
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtGetNdkIsUnsupportedVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9q")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9e").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "Buck is configured to use Android NDK version r9e at "
            + "ndk.dir or ANDROID_NDK or NDK_HOME. The found version is r9q located at "
            + ndkDir);
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtGetNdkTargetVersionIsEmpty() throws IOException {
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9a", "r9a-rc2");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion(" ").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(NDK_TARGET_VERSION_IS_EMPTY_MESSAGE);
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtGetNdkVersionFileIsEmpty() throws IOException {
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9a", "");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion(" ").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        tmpDir.getRoot() + " does not contain a valid properties " + "file for Android NDK.");
    resolver.getNdkOrThrow();
  }

  @Test
  public void getNdkSpecificVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d (64-bit)")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9d").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir =
        createTmpNdkVersions(
            NDK_POST_R11_VERSION_FILENAME,
            "ndk-dir-new",
            "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];

    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("11.2").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkInexactMatchVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d (64-bit)")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir =
        createTmpNdkVersions(
            NDK_POST_R11_VERSION_FILENAME,
            "ndk-dir-new",
            "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];
    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("11").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkNewestVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-old", "r9e")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir =
        createTmpNdkVersions(
            NDK_POST_R11_VERSION_FILENAME,
            "ndk-dir-new",
            "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];
    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkOrAbsent() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-old", "r9e")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(ndkDir, resolver.getNdkOrAbsent().get());

    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(Optional.empty(), resolver.getNdkOrAbsent());
  }

  @Test
  public void scanNdkSpecificVersion() throws IOException {
    Path expectedPath =
        createTmpNdkVersions(
            NDK_PRE_R11_VERSION_FILENAME,
            "ndk-dir-r9a",
            "r9a",
            "ndk-dir-r9b",
            "r9b",
            "ndk-dir-r9c",
            "r9c")[1];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9b").build());

    assertEquals(expectedPath, resolver.getNdkOrThrow());
  }

  @Test
  public void throwAtscanNdkSpecificVersion() throws IOException {
    createTmpNdkVersions(
        NDK_PRE_R11_VERSION_FILENAME,
        "ndk-dir-r9a",
        "r9a",
        "ndk-dir-r9b",
        "r9b",
        "ndk-dir-r9c",
        "r9c");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r42z").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "Target NDK version r42z is not available. The following "
            + "versions are available: r9c, r9b, r9a");
    resolver.getNdkOrThrow();
  }

  @Test
  public void scanNdkInexactMatchVersion() throws IOException {
    Path expectedPath =
        createTmpNdkVersions(
            NDK_POST_R11_VERSION_FILENAME,
            "ndk-dir-r11",
            "Pkg.Desc = Android NDK\nPkg.Revision = 11.2",
            "ndk-dir-r12",
            "Pkg.Desc = Android NDK\nPkg.Revision = 12.4",
            "ndk-dir-r13",
            "Pkg.Desc = Android NDK\nPkg.Revision = 13.2")[2];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("13").build());

    assertEquals(expectedPath, resolver.getNdkOrThrow());
  }

  @Test
  public void scanNdkNewestVersion() throws IOException {
    Path expectedPath =
        createTmpNdkVersions(
            NDK_POST_R11_VERSION_FILENAME,
            "ndk-dir-r11",
            "Pkg.Desc = Android NDK\nPkg.Revision = 11.2",
            "ndk-dir-r12",
            "Pkg.Desc = Android NDK\nPkg.Revision = 12.4",
            "ndk-dir-r13",
            "Pkg.Desc = Android NDK\nPkg.Revision = 13.2")[2];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(expectedPath, resolver.getNdkOrThrow());
  }

  @Test
  public void testFindAndroidNdkDirThrowOnUnsupportedVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9q")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9e").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "Buck is configured to use Android NDK version r9e at "
            + "ndk.dir or ANDROID_NDK or NDK_HOME. The found version is r9q located at "
            + ndkDir);
    resolver.getNdkOrThrow();
  }

  @Test
  public void notEqualWhenNdkIsDifferent() throws IOException {
    createTmpNdkVersions(
        "ndk-dir-r9a", "r9a",
        "ndk-dir-r9b", "r9b");
    DefaultAndroidDirectoryResolver resolver1 =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9a").build());
    DefaultAndroidDirectoryResolver resolver2 =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9b").build());

    assertNotEquals(resolver1, resolver2);
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersionEmptyRequested() throws IOException {
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9a", "r9a-rc2");
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion(" ").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(NDK_TARGET_VERSION_IS_EMPTY_MESSAGE);
    resolver.getNdkOrThrow();
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

  @Test
  public void testGetNdkFromBuckconfig() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkPath(ndkDir.toString())
                .build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkRepositoryPath(tmpDir.getRoot().toString())
                .build());
    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void testErrorMessageFromBuckconfigNdk() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkPath(ndkDir.resolve("wrong").toString())
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ndk.ndk_path",
            ndkDir.resolve("wrong")));
    resolver.getNdkOrThrow();

    resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkRepositoryPath(ndkDir.resolve("also-wrong").toString())
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ndk.ndk_repository_path",
            ndkDir.resolve("also-wrong")));
    resolver.getNdkOrThrow();
  }

  @Test
  public void testEnvironmentVariableOverridesNdkConfig() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d")[0];
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(
                "ANDROID_NDK_REPOSITORY", ndkDir.resolve("env1").toString(),
                "ANDROID_NDK", ndkDir.resolve("env2").toString(),
                "NDK_HOME", ndkDir.resolve("env3").toString()),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkRepositoryPath(ndkDir.resolve("config1").toString())
                .setNdkPath(ndkDir.resolve("config2").toString())
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            DefaultAndroidDirectoryResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ndk.ndk_path",
            ndkDir.resolve("config2")));
    resolver.getNdkOrThrow();
  }

  private Path[] createTmpNdkVersions(String filename, String... directoryNamesAndVersionStrings)
      throws IOException {
    Path[] ret = new Path[directoryNamesAndVersionStrings.length / 2];
    for (int i = 0; i < directoryNamesAndVersionStrings.length / 2; i++) {
      String folderName = directoryNamesAndVersionStrings[i * 2];
      String version = directoryNamesAndVersionStrings[(i * 2) + 1];
      ret[i] = tmpDir.newFolder(folderName);
      Path releaseFile = tmpDir.newFile(folderName + "/" + filename);
      MoreFiles.writeLinesToFile(ImmutableList.of(version), releaseFile);
    }
    return ret;
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
