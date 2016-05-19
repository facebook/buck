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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakePropertyFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PropertyFinder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class DefaultAndroidDirectoryResolverTest {

  @Rule
  public TemporaryPaths tmpDir = new TemporaryPaths();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getAbsentSdkNdk() {
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent()
    );

    assertEquals(Optional.<Path>absent(), resolver.getSdkOrAbsent());
    assertEquals(Optional.<Path>absent(), resolver.getNdkOrAbsent());
  }

  @Test
  public void throwAtAbsentSdk() {
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent()
    );

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.SDK_NOT_FOUND_MESSAGE);
    resolver.getSdkOrThrow();
  }

  @Test
  public void throwAtAbsentBuildTools() {
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent()
    );

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.TOOLS_NEED_SDK_MESSAGE);
    resolver.getBuildToolsOrThrow();
  }

  @Test
  public void throwAtAbsentNdk() {
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent()
    );

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(DefaultAndroidDirectoryResolver.NDK_NOT_FOUND_MESSAGE);
    resolver.getNdkOrThrow();
  }

  @Test
  public void throwAtSdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.of(file),
        Optional.<Path>absent(),
        Optional.<Path>absent()
    );

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Android SDK path [" + file + "] is not a directory.");
    resolver.getSdkOrThrow();
  }

  @Test
  public void throwAtNdkPathIsNotDirectory() throws IOException {
    Path file = tmpDir.getRoot().resolve(tmpDir.newFile("file"));
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.of(file),
        Optional.<Path>absent()
    );

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(file + " does not contain a valid properties file for " +
        "Android NDK.");
    resolver.getNdkOrThrow();
  }

  @Test
  public void getNdkSpecificVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d (64-bit)")[0];
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.of("r9d"),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir = createTmpNdkVersions(
        NDK_POST_R11_VERSION_FILENAME,
        "ndk-dir-new",
        "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];

    resolver = createResolver(
        Optional.of("11.2"),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkInexactMatchVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9d (64-bit)")[0];
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.of("r9"),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir = createTmpNdkVersions(
        NDK_POST_R11_VERSION_FILENAME,
        "ndk-dir-new",
        "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];
    resolver = createResolver(
        Optional.of("11"),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkNewestVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-old", "r9e")[0];
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());

    ndkDir = createTmpNdkVersions(
        NDK_POST_R11_VERSION_FILENAME,
        "ndk-dir-new",
        "Pkg.Desc = Android NDK\nPkg.Revision = 11.2.2725575")[0];
    resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrThrow());
  }

  @Test
  public void getNdkOrAbsent() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-old", "r9e")[0];
    DefaultAndroidDirectoryResolver resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.of(ndkDir),
        Optional.<Path>absent());

    assertEquals(ndkDir, resolver.getNdkOrAbsent().get());

    resolver = createResolver(
        Optional.<String>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent());

    assertEquals(Optional.absent(), resolver.getNdkOrAbsent());
  }

  @Test
  public void testFindAndroidNdkDirThrowOnFailedRead() throws IOException {
    Path ndkDir = tmpDir.newFolder("ndk-dir");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.<String>absent(),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.of(ndkDir),
                Optional.<Path>absent()));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(ndkDir.toAbsolutePath() + " does not contain a valid " +
        "properties file for Android NDK.");
    androidDirectoryResolver.getNdkOrThrow();
  }

  @Test
  public void testFindAndroidNdkDirThrowOnUnsupportedVersion() throws IOException {
    Path ndkDir = createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir", "r9q")[0];
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9e"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.of(ndkDir),
                Optional.<Path>absent()));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Buck is configured to use Android NDK version r9e at " +
        "ndk.dir or ANDROID_NDK or NDK_HOME. The found version is r9q located at " + ndkDir);
    androidDirectoryResolver.getNdkOrThrow();
    }

  @Test
  public void testFindAndroidNdkDirScanThrowsOnFail() throws IOException {
    tmpDir.newFolder("ndk-dir-r9a");
    tmpDir.newFolder("ndk-dir-r9b");
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9c", "r9c");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9e"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Couldn't find a valid NDK under " + tmpDir.getRoot());
    androidDirectoryResolver.getNdkOrThrow();
  }

  @Test
  public void testFindAndroidNdkDirScanTakesNewest() throws IOException {
    Path releaseDir = createTmpNdkVersions(
        NDK_PRE_R11_VERSION_FILENAME,
        "ndk-dir-r9a", "r9a",
        "ndk-dir-r9b", "r9b (64-bit)",
        "ndk-dir-r9c", "r9c (64-bit)"
    )[2];
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.<String>absent(),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    assertEquals(Optional.of(releaseDir), androidDirectoryResolver.getNdkOrAbsent());

    releaseDir = createTmpNdkVersions(
        NDK_POST_R11_VERSION_FILENAME,
        "ndk-dir-r11c", "Pkg.Revision = 11.2.2725575",
        "ndk-dir-r11b", "Pkg.Revision = 11.1.32"
    )[0];
    androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.<String>absent(),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    assertEquals(Optional.of(releaseDir),
        androidDirectoryResolver.getNdkOrAbsent());
  }

  @Test
  public void notEqualWhenNdkIsDifferent() throws IOException {
    createTmpNdkVersions(
        "ndk-dir-r9a", "r9a",
        "ndk-dir-r9b", "r9b"
    );
    DefaultAndroidDirectoryResolver androidDirectoryResolver1 =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9a"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));
    DefaultAndroidDirectoryResolver androidDirectoryResolver2 =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9b"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    assertNotEquals(androidDirectoryResolver1, androidDirectoryResolver2);
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersion() throws IOException {
    Path expectedPath = createTmpNdkVersions(
        NDK_PRE_R11_VERSION_FILENAME,
        "ndk-dir-r9a", "r9a",
        "ndk-dir-r9b", "r9b",
        "ndk-dir-r9c", "r9c"
    )[1];
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9b"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    assertEquals(Optional.of(expectedPath),
        androidDirectoryResolver.getNdkOrAbsent());
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersionInexactMatch() throws IOException {
    Path expectedPath = createTmpNdkVersions(
        NDK_PRE_R11_VERSION_FILENAME,
        "ndk-dir-r9a", "r9a-rc2",
        "ndk-dir-r9b", "r9b-rc3 (64-bit)",
        "ndk-dir-r9c", "r9c"
    )[1];
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9b"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    assertEquals(Optional.of(expectedPath),
        androidDirectoryResolver.getNdkOrAbsent());
  }

  @Test
    public void testFindAndroidNdkDirScanTakesVersionEmptyRequested() throws IOException {
    createTmpNdkVersions(NDK_PRE_R11_VERSION_FILENAME, "ndk-dir-r9a", "r9a-rc2");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of(""),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Couldn't find a valid NDK under " + tmpDir.getRoot());
    androidDirectoryResolver.getNdkOrThrow();
  }

  @Test
  public void testFindAndroidNdkDirScanTakesVersionEmptyFound() throws IOException {
    createTmpNdkVersions("ndk-dir-r9a", "");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            Optional.<String>absent(),
            Optional.of("r9e"),
            createPropertiesFinder(
                Optional.<Path>absent(),
                Optional.<Path>absent(),
                Optional.of(tmpDir.getRoot())));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("Couldn't find a valid NDK under " + tmpDir.getRoot());
    androidDirectoryResolver.getNdkOrThrow();
  }

  @Test
  public void buildToolsFallsBacktoPlatformTools() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "platform-tools");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("platform-tools"));
  }

  @Test
  public void buildToolsAPIVersionFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/android-4.2.2");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("android-4.2.2"));
  }

  @Test
  public void buildToolsWithBuildToolsPrefix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/build-tools-17.2.2");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("build-tools-17.2.2"));
  }

  @Test
  public void buildToolsInvalidPrefixThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(sdkDir, "build-tools/foobar-17.2.2");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));

    expectedException.expect(HumanReadableException.class);
      expectedException.expectMessage("foobar-17.2.2");
    androidDirectoryResolver.getBuildToolsOrThrow();

  }

  @Test
  public void buildToolsEmptyDirectoryThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    sdkDir.resolve("build-tools").toFile().mkdir();
    sdkDir.resolve("tools").toFile().mkdir();
    Path toolsDir = sdkDir.resolve("tools").toAbsolutePath();
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("null was empty, but should have contained a subdirectory " +
        "with build tools. Install them using the Android SDK Manager (" + toolsDir +
        File.separator + "android).");
    androidDirectoryResolver.getBuildToolsOrThrow();
  }

  @Test
  public void buildToolsRCVersionsFound() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir,
        "build-tools/23.0.0_rc1");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("23.0.0_rc1"));
  }

  @Test
  public void buildToolsRCAndNonRCMix() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir,
        "build-tools/22.0.0",
        "build-tools/23.0.0_rc1");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("23.0.0_rc1"));
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
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.<String>absent(),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionIsSelected() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir,
        "build-tools/16.0.0",
        "build-tools/17.0.0",
        "build-tools/18.0.0");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.of("17.0.0"),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));
    assertThat(
        androidDirectoryResolver.getBuildToolsOrThrow().getFileName().toString(),
        Matchers.equalTo("17.0.0"));
  }

  @Test
  public void targettedBuildToolsVersionNotAvailableThrows() throws IOException {
    Path sdkDir = tmpDir.newFolder("sdk");
    createBuildToolsVersions(
        sdkDir,
        "build-tools/18.0.0");
    DefaultAndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            new ProjectFilesystem(tmpDir.getRoot()),
            /* targetBuildToolsVersion */ Optional.of("2.0.0"),
            /* targetNdkVersion */ Optional.<String>absent(),
            createPropertiesFinder(
                Optional.of(sdkDir),
                Optional.<Path>absent(),
                Optional.<Path>absent()));

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("2.0.0");
    androidDirectoryResolver.getBuildToolsOrThrow();
  }

  private DefaultAndroidDirectoryResolver createResolver(
      Optional<String> targetNdkVersion,
      Optional<Path> sdk,
      Optional<Path> ndk,
      Optional<Path> ndkRepository
  ) {
    return new DefaultAndroidDirectoryResolver(
        new ProjectFilesystem(tmpDir.getRoot()),
        Optional.<String>absent(),
        targetNdkVersion,
        createPropertiesFinder(sdk, ndk, ndkRepository));
  }

  private PropertyFinder createPropertiesFinder(
      Optional<Path> sdk,
      Optional<Path> ndk,
      Optional<Path> ndkRepository) {
    return new FakePropertyFinder(
        ImmutableMap.of("sdk.dir", sdk, "ndk.dir", ndk, "ndk.repository", ndkRepository));
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

  private Path[] createBuildToolsVersions(
      Path sdkDir,
      String... directoryNames) throws IOException {
    Path[] ret = new Path[directoryNames.length];
    for (int i = 0; i < directoryNames.length; i++) {
      File folder = sdkDir.resolve(directoryNames[i]).toFile();
      assertThat(folder.mkdirs(), Matchers.is(true));
      ret[i] = folder.toPath();
    }
    return ret;
  }
}
