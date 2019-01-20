/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.config;

import com.facebook.buck.intellij.ideabuck.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Unit test for BuckExecutableDetector. */
public class BuckExecutableDetectorTest {

  @Test
  public void findsExecutableNotOnPathButRelativeToAndroidSdk() throws IOException {
    Path tempDirectory =
        Files.createTempDirectory(BuckExecutableDetectorTest.class.getSimpleName()).toRealPath();
    Path path1 = tempDirectory.resolve("path1");
    Path path2 = tempDirectory.resolve("path2");
    Path androidSdk = Files.createDirectories(tempDirectory.resolve("android_sdk"));
    Path platformTools = Files.createDirectories(androidSdk.resolve("platform-tools"));
    String exeName = Platform.detect() == Platform.WINDOWS ? "adb.exe" : "adb";
    Path expectedAdb = Files.createFile(platformTools.resolve(exeName));
    Assume.assumeTrue(
        "Should be able to make adb executable", expectedAdb.toFile().setExecutable(true));
    ImmutableMap<String, String> env =
        ImmutableMap.of(
            "PATH",
            path1.toString() + File.pathSeparator + path2.toString(),
            "ANDROID_SDK",
            androidSdk.toString());
    BuckExecutableDetector detector = new BuckExecutableDetector.Impl(env);
    Assert.assertEquals(expectedAdb.toString(), detector.getAdbExecutable());
  }
}
