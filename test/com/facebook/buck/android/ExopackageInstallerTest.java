/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.NativeExoHelper;
import com.facebook.buck.android.exopackage.PackageInfo;
import com.facebook.buck.android.exopackage.RealAndroidDevice;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@SuppressWarnings("PMD.AddEmptyString")
public class ExopackageInstallerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testParsePathAndPackageInfo() {
    String lines =
        "package:/data/app/com.facebook.katana-1.apk\r\n"
            + "  Package [com.facebook.katana] (4229ce68):\r\n"
            + "    userId=10145 gids=[1028, 1015, 3003]\r\n"
            + "    pkg=Package{42690b80 com.facebook.katana}\r\n"
            + "    codePath=/data/app/com.facebook.katana-1.apk\r\n"
            + "    resourcePath=/data/app/com.facebook.katana-1.apk\r\n"
            + "    nativeLibraryPath=/data/app-lib/com.facebook.katana-1\r\n"
            + "    versionCode=1640376 targetSdk=14\r\n"
            + "    versionName=8.0.0.0.23\r\n"
            + "";
    Optional<PackageInfo> optionalInfo =
        RealAndroidDevice.parsePathAndPackageInfo("com.facebook.katana", lines);

    assertTrue(optionalInfo.isPresent());
    PackageInfo info = optionalInfo.get();

    assertEquals("/data/app/com.facebook.katana-1.apk", info.apkPath);
    assertEquals("/data/app-lib/com.facebook.katana-1", info.nativeLibPath);
    assertEquals("1640376", info.versionCode);
  }

  @Test
  public void testParsePathAndPackageInfoOnLollipop() {
    String lines =
        "package:/data/app/com.facebook.buck.android.agent-1.apk\r\n"
            + "  Package [com.facebook.buck.android.agent] (3f784d07):\r\n"
            + "    userId=10062 gids=[]\r\n"
            + "    pkg=Package{81b1e34 com.facebook.buck.android.agent}\r\n"
            + "    codePath=/data/app/com.facebook.buck.android.agent-1\r\n"
            + "    resourcePath=/data/app/com.facebook.buck.android.agent-1\r\n"
            + "    legacyNativeLibraryDir=/data/app/com.facebook.buck.android.agent-1/lib\r\n"
            + "    primaryCpuAbi=armeabi-v7a\r\n"
            + "    secondaryCpuAbi=null\r\n"
            + "    versionCode=3 targetSdk=19\r\n"
            + "    versionName=3\r\n"
            + "";
    Optional<PackageInfo> optionalInfo =
        RealAndroidDevice.parsePathAndPackageInfo("com.facebook.buck.android.agent", lines);

    assertTrue(optionalInfo.isPresent());
    PackageInfo info = optionalInfo.get();

    assertEquals("/data/app/com.facebook.buck.android.agent-1/base.apk", info.apkPath);
    assertEquals("/data/app/com.facebook.buck.android.agent-1/lib", info.nativeLibPath);
    assertEquals("3", info.versionCode);
  }

  @Test
  public void testParseOnlyPackageInfo() {
    String lines =
        "1\r\n"
            + "  Package [com.facebook.katana] (4229ce68):\r\n"
            + "    userId=10145 gids=[1028, 1015, 3003]\r\n"
            + "    pkg=Package{42690b80 com.facebook.katana}\r\n"
            + "    codePath=/data/app/com.facebook.katana-1.apk\r\n"
            + "    resourcePath=/data/app/com.facebook.katana-1.apk\r\n"
            + "    nativeLibraryPath=/data/app-lib/com.facebook.katana-1\r\n"
            + "    versionCode=1640376 targetSdk=14\r\n"
            + "    versionName=8.0.0.0.23\r\n"
            + "";
    Optional<PackageInfo> optionalInfo =
        RealAndroidDevice.parsePathAndPackageInfo("com.facebook.katana", lines);

    assertFalse(optionalInfo.isPresent());
  }

  @Test
  public void testChunkArgs() {
    assertEquals(ImmutableList.of(), RealAndroidDevice.chunkArgs(ImmutableList.of(), 8));

    assertEquals(
        ImmutableList.of(ImmutableList.of("abcd", "efg")),
        RealAndroidDevice.chunkArgs(ImmutableList.of("abcd", "efg"), 8));

    assertEquals(
        ImmutableList.of(ImmutableList.of("abcd", "efg"), ImmutableList.of("hijkl")),
        RealAndroidDevice.chunkArgs(ImmutableList.of("abcd", "efg", "hijkl"), 8));
  }

  @Test
  public void testFilterLibrariesForAbi() {
    Path libsDir = Paths.get("example");
    ImmutableMultimap<String, Path> allLibs =
        ImmutableMultimap.of(
            Strings.repeat("a", 40), libsDir.resolve("libs/armeabi-v7a/libmy1.so"),
            Strings.repeat("b", 40), libsDir.resolve("libs/armeabi-v7a/libmy2.so"),
            Strings.repeat("c", 40), libsDir.resolve("libs/armeabi/libmy2.so"),
            Strings.repeat("d", 40), libsDir.resolve("libs/armeabi/libmy3.so"),
            Strings.repeat("e", 40), libsDir.resolve("libs/x86/libmy1.so"));

    assertEquals(
        ImmutableSet.of(Strings.repeat("a", 40), Strings.repeat("b", 40)),
        NativeExoHelper.filterLibrariesForAbi(libsDir, allLibs, "armeabi-v7a", ImmutableSet.of())
            .keySet());

    assertEquals(
        ImmutableSet.of(Strings.repeat("d", 40)),
        NativeExoHelper.filterLibrariesForAbi(
                libsDir, allLibs, "armeabi", ImmutableSet.of("libmy1.so", "libmy2.so"))
            .keySet());
  }

  @Test
  public void testParseExopackageInfoMetadata() throws IOException {
    String illegalLine = "no_space_in_this_line_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    thrown.expectMessage("Illegal line in metadata file: " + illegalLine);

    Path baseDir = Paths.get("basedir");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeLinesToPath(
        ImmutableList.of(
            ".some_config",
            ".more_config with_spaces",
            "filename.jar aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "dir/anotherfile.jar bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
        Paths.get("metadata.txt"));

    assertEquals(
        ImmutableMultimap.of(
            Strings.repeat("a", 40), Paths.get("basedir/filename.jar"),
            Strings.repeat("b", 40), Paths.get("basedir/dir/anotherfile.jar")),
        ExopackageInstaller.parseExopackageInfoMetadata(
            Paths.get("metadata.txt"), baseDir, filesystem));

    filesystem.writeLinesToPath(
        ImmutableList.of("filename.jar aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", illegalLine),
        Paths.get("metadata.txt"));

    ExopackageInstaller.parseExopackageInfoMetadata(Paths.get("metadata.txt"), baseDir, filesystem);
  }
}
