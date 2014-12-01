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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.util.environment.Platform;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit tests for {@link AppleCxxPlatform}.
 */
public class AppleCxxPlatformTest {

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/apple-cxx-platform");

    AppleSdkPaths appleSdkPaths =
        ImmutableAppleSdkPaths.builder()
            .toolchainPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleCxxPlatform appleCxxPlatform =
        new AppleCxxPlatform(
            new Flavor("iphoneos-8-armv7-arm64"),
            Platform.MACOS,
            appleSdkPaths
        );

    assertEquals(
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        appleCxxPlatform.getCc().toString());
    assertEquals(
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        appleCxxPlatform.getCxx().toString());
    assertEquals(
        root.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        appleCxxPlatform.getAr().toString());
  }
}
