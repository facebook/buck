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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

@SuppressWarnings("PMD.AddEmptyString")
public class ExopackageInstallerTest {
  @Test
  public void testScanSecondaryDexDir() throws Exception {
    String output =
        "exopackage_temp-secondary-abcdefg.dex.jar-588103794.tmp\r\n" +
        "lock\r\n" +
        "metadata.txt\r\n" +
        "secondary-0fa1f9cfb3c0effa8000d2d86d267985b158df9f.dex.jar\r\n" +
        "secondary-07fc80d2de21bd1dd57be0728fdb6c14190c3386.dex.jar\r\n" +
        "secondary-2add18058985241f7999eb026868cebb9ef63379.dex.jar\r\n" +
        "";
    ImmutableSet<String> requiredHashes = ImmutableSet.of(
        "0fa1f9cfb3c0effa8000d2d86d267985b158df9f",
        "2add18058985241f7999eb026868cebb9ef63379",
        "97d21318d1d5dd298f6ee932916c6ee949fe760e");
    ImmutableSet.Builder<String> foundHashesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> toDeleteBuilder = ImmutableSet.builder();

    ExopackageInstaller.scanSecondaryDexDir(
        output,
        requiredHashes,
        foundHashesBuilder,
        toDeleteBuilder);

    assertEquals(
        ImmutableSet.of(
            "0fa1f9cfb3c0effa8000d2d86d267985b158df9f",
            "2add18058985241f7999eb026868cebb9ef63379"),
        foundHashesBuilder.build());

    assertEquals(
        ImmutableSet.of(
            "exopackage_temp-secondary-abcdefg.dex.jar-588103794.tmp",
            "metadata.txt",
            "secondary-07fc80d2de21bd1dd57be0728fdb6c14190c3386.dex.jar"),
        toDeleteBuilder.build());
  }

  @Test
  public void testParsePackageInfo() {
    String lines =
        "  Package [com.facebook.katana] (4229ce68):\r\n" +
        "    userId=10145 gids=[1028, 1015, 3003]\r\n" +
        "    pkg=Package{42690b80 com.facebook.katana}\r\n" +
        "    codePath=/data/app/com.facebook.katana-1.apk\r\n" +
        "    resourcePath=/data/app/com.facebook.katana-1.apk\r\n" +
        "    nativeLibraryPath=/data/app-lib/com.facebook.katana-1\r\n" +
        "    versionCode=1640376 targetSdk=14\r\n" +
        "    versionName=8.0.0.0.23\r\n" +
        "";
    Optional<ExopackageInstaller.PackageInfo> optionalInfo = ExopackageInstaller.parsePackageInfo(
        "com.facebook.katana",
        lines);

    assertTrue(optionalInfo.isPresent());
    ExopackageInstaller.PackageInfo info = optionalInfo.get();

    assertEquals("/data/app/com.facebook.katana-1.apk", info.apkPath);
    assertEquals("/data/app-lib/com.facebook.katana-1", info.nativeLibPath);
    assertEquals("1640376", info.versionCode);
  }

  @Test
  public void testChunkArgs() {
    assertEquals(
        ImmutableList.of(),
        ExopackageInstaller.chunkArgs(ImmutableList.<String>of(), 8));

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("abcd", "efg")),
        ExopackageInstaller.chunkArgs(ImmutableList.of("abcd", "efg"), 8));

    assertEquals(
        ImmutableList.of(
            ImmutableList.of("abcd", "efg"),
            ImmutableList.of("hijkl")),
        ExopackageInstaller.chunkArgs(ImmutableList.of("abcd", "efg", "hijkl"), 8));
  }
}
