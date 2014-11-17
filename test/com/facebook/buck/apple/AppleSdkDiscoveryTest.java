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

import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit tests for {@link AppleSdkDiscovery}.
 */
public class AppleSdkDiscoveryTest {
  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/sdk-discovery");
    ImmutableMap<String, ImmutableAppleSdkPaths> expected = ImmutableMap.of(
        "macosx10.9",
        ImmutableAppleSdkPaths.builder()
            .toolchainPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/MacOSX.platform/Developer"))
            .sdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build(),
        "iphoneos8.0",
        ImmutableAppleSdkPaths.builder()
            .toolchainPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build(),
        "iphonesimulator8.0",
        ImmutableAppleSdkPaths.builder()
            .toolchainPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .sdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build());
    assertEquals(expected, AppleSdkDiscovery.discoverAppleSdkPaths(root));
  }
}
