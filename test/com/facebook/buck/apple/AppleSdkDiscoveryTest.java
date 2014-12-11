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

import static org.hamcrest.Matchers.equalTo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleSdkDiscoveryTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    Path path = temp.newFolder().toPath().toAbsolutePath();

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        path.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        path,
        toolchainPaths);

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithUnrecognizedPlatform() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/sdk-unknown-platform-discovery");
    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        root,
        toolchainPaths);

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithBadSymlink() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/sdk-bad-symlink-discovery");
    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        root,
        toolchainPaths);

    assertEquals(0, sdks.size());
  }

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/sdk-discovery");
    ImmutableAppleSdk macosx109Sdk =
        ImmutableAppleSdk.builder()
            .name("macosx10.9")
            .version("10.9")
            .applePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .build();
    ImmutableAppleSdkPaths macosx109Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/MacOSX.platform/Developer"))
            .sdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableAppleSdk iphoneos80Sdk =
        ImmutableAppleSdk.builder()
            .name("iphoneos8.0")
            .version("8.0")
            .applePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphoneos80Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator80Sdk =
        ImmutableAppleSdk.builder()
            .name("iphonesimulator8.0")
            .version("8.0")
            .applePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphonesimulator80Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .sdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build();

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault.xctoolchain"));

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphoneos80Sdk.withName("iphoneos"), iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphonesimulator80Sdk.withName("iphonesimulator"), iphonesimulator80Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(root, toolchainPaths),
        equalTo(expected));
  }

  @Test
  public void multipleAppleSdkPathsPerPlatformBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/sdk-multi-version-discovery");
    ImmutableAppleSdk macosx109Sdk =
        ImmutableAppleSdk.builder()
            .name("macosx10.9")
            .version("10.9")
            .applePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .build();
    ImmutableAppleSdkPaths macosx109Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/MacOSX.platform/Developer"))
            .sdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableAppleSdk iphoneos80Sdk =
        ImmutableAppleSdk.builder()
            .name("iphoneos8.0")
            .version("8.0")
            .applePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphoneos80Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator80Sdk =
        ImmutableAppleSdk.builder()
            .name("iphonesimulator8.0")
            .version("8.0")
            .applePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphonesimulator80Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .sdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .build();

    ImmutableAppleSdk iphoneos81Sdk =
        ImmutableAppleSdk.builder()
            .name("iphoneos8.1")
            .version("8.1")
            .applePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_1")
            .build();
    ImmutableAppleSdkPaths iphoneos81Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .sdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator81Sdk =
        ImmutableAppleSdk.builder()
            .name("iphonesimulator8.1")
            .version("8.1")
            .applePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_1")
            .build();
    ImmutableAppleSdkPaths iphonesimulator81Paths =
        ImmutableAppleSdkPaths.builder()
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .platformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .sdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphoneos81Sdk, iphoneos81Paths)
            .put(iphoneos81Sdk.withName("iphoneos"), iphoneos81Paths)
            .put(iphonesimulator81Sdk, iphonesimulator81Paths)
            .put(iphonesimulator81Sdk.withName("iphonesimulator"), iphonesimulator81Paths)
            .build();

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault.xctoolchain"));

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(root, toolchainPaths),
        equalTo(expected));
  }
}
