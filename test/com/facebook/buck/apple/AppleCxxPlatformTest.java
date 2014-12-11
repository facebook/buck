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
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.environment.Platform;

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;

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
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleCxxPlatform appleCxxPlatform =
        new AppleCxxPlatform(
            Platform.MACOS,
            ApplePlatform.IPHONEOS,
            "iphoneos8.0",
            "7.0",
            "armv7",
            appleSdkPaths
        );

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    assertEquals(
        new Flavor("iphoneos8.0-armv7"),
        appleCxxPlatform.asFlavor());
    assertEquals(
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        appleCxxPlatform.getCc().getCommandPrefix(resolver).get(0));
    assertThat(
        appleCxxPlatform.getCflags(),
        hasConsecutiveItems(
            "-isysroot",
            root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk").toString()));
    assertThat(
        appleCxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        appleCxxPlatform.getCflags(),
        hasConsecutiveItems("-mios-version-min=7.0"));

    assertEquals(
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        appleCxxPlatform.getCxx().getCommandPrefix(resolver).get(0));
    assertEquals(
        root.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        appleCxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }
}
