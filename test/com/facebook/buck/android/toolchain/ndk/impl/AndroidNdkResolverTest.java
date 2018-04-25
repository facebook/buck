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

package com.facebook.buck.android.toolchain.ndk.impl;

import static com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkResolver.NDK_POST_R11_VERSION_FILENAME;
import static com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkResolver.NDK_PRE_R11_VERSION_FILENAME;
import static com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkResolver.NDK_TARGET_VERSION_IS_EMPTY_MESSAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.android.FakeAndroidBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidNdkResolverTest {

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getAbsentNdk() {
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("");
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtAbsentNdk() {
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(), ImmutableMap.of(), AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(AndroidNdkResolver.NDK_NOT_FOUND_MESSAGE);
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtNdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", file.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(AndroidNdkResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE, "ANDROID_NDK", file));
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtGetNdkDirectoryIsEmpty() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("11.2").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkInexactMatchVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d (64-bit)")[0];
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("11").build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkNewestVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-old", "r9e")[0];
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", ndkDir.toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(ndkDir, resolver.getNdkOrThrow());
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            AndroidNdkHelper.DEFAULT_CONFIG);

    assertEquals(expectedPath, resolver.getNdkOrThrow());
  }

  @Test
  public void testFindAndroidNdkDirThrowOnUnsupportedVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9q")[0];
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver1 =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9a").build());
    AndroidNdkResolver resolver2 =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion("r9b").build());

    assertNotEquals(resolver1, resolver2);
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersionEmptyRequested() throws IOException {
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9a", "r9a-rc2");
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of("ANDROID_NDK_REPOSITORY", tmpDir.getRoot().toString()),
            FakeAndroidBuckConfig.builder().setNdkVersion(" ").build());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(NDK_TARGET_VERSION_IS_EMPTY_MESSAGE);
    resolver.getNdkOrThrow();
  }

  @Test
  public void testGetNdkFromBuckconfig() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d")[0];
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkPath(ndkDir.toString())
                .build());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    resolver =
        new AndroidNdkResolver(
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
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkPath(ndkDir.resolve("wrong").toString())
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            AndroidNdkResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ndk.ndk_path",
            ndkDir.resolve("wrong")));
    resolver.getNdkOrThrow();

    resolver =
        new AndroidNdkResolver(
            tmpDir.getRoot().getFileSystem(),
            ImmutableMap.of(),
            FakeAndroidBuckConfig.builder()
                .setNdkVersion("r9d")
                .setNdkRepositoryPath(ndkDir.resolve("also-wrong").toString())
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        String.format(
            AndroidNdkResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
            "ndk.ndk_repository_path",
            ndkDir.resolve("also-wrong")));
    resolver.getNdkOrThrow();
  }

  @Test
  public void testEnvironmentVariableOverridesNdkConfig() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d")[0];
    AndroidNdkResolver resolver =
        new AndroidNdkResolver(
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
            AndroidNdkResolver.INVALID_DIRECTORY_MESSAGE_TEMPLATE,
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
      MostFiles.writeLinesToFile(ImmutableList.of(version), releaseFile);
    }
    return ret;
  }
}
