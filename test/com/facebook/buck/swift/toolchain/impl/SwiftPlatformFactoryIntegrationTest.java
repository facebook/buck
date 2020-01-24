/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.swift.toolchain.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftPlatformFactoryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private AppleToolchain createAppleToolchain(Path toolchainPath) {
    return AppleToolchain.builder()
        .setIdentifier("com.apple.dt.toolchain.XcodeDefault")
        .setPath(toolchainPath)
        .setVersion("1")
        .build();
  }

  private AppleSdk createAppleSdk(AppleToolchain... toolchain) {
    AppleSdk.Builder appleSdkBuilder =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("iphoneos8.0")
            .setVersion("8.0");
    appleSdkBuilder.setToolchains(ImmutableList.copyOf(toolchain));
    return appleSdkBuilder.build();
  }

  private AppleSdkPaths createAppleSdkPaths(Path developerDir, Path... toolchainPaths) {
    AppleSdkPaths.Builder appleSdkPathsBuilder =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"));
    for (Path toolchainPath : toolchainPaths) {
      appleSdkPathsBuilder.addToolchainPaths(toolchainPath);
    }
    return appleSdkPathsBuilder.build();
  }

  private Tool swiftcTool;
  private Tool swiftStdTool;

  @Before
  public void setUp() {
    swiftcTool = VersionedTool.of("foo", FakeSourcePath.of("swiftc"), "1.0");
    swiftStdTool = VersionedTool.of("foo", FakeSourcePath.of("swift-std"), "1.0");
  }

  @Test
  public void testBuildSwiftPlatformWithEmptyToolchainPaths() throws IOException {
    Path developerDir = tmp.newFolder("Developer");
    SwiftTargetTriple triple = SwiftTargetTriple.of("x86_64", "apple", "ios", "9.3");
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            createAppleSdk(),
            createAppleSdkPaths(developerDir),
            swiftcTool,
            Optional.of(swiftStdTool),
            true,
            triple);
    assertThat(swiftPlatform.getSwiftStdlibTool().get(), equalTo(swiftStdTool));
    assertThat(swiftPlatform.getSwiftc(), equalTo(swiftcTool));
    assertThat(swiftPlatform.getSwiftRuntimePathsForBundling(), empty());
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), empty());
    assertThat(swiftPlatform.getSwiftTarget(), equalTo(triple));
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithoutTools() throws IOException {
    Path developerDir = tmp.newFolder("Developer");
    Path toolchainDir = tmp.newFolder("foo");
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            createAppleSdk(createAppleToolchain(toolchainDir)),
            createAppleSdkPaths(developerDir, toolchainDir),
            swiftcTool,
            Optional.of(swiftStdTool),
            true,
            SwiftTargetTriple.of("x86_64", "apple", "ios", "9.3"));
    assertThat(swiftPlatform.getSwiftRuntimePathsForBundling(), empty());
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), empty());
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithTools() throws IOException {
    Path developerDir = tmp.newFolder("Developer");
    tmp.newFolder("foo/usr/lib/swift/iphoneos");
    tmp.newFile("foo/usr/lib/swift/iphoneos/libswiftCore.dylib");
    tmp.newFolder("foo2/usr/lib/swift_static/iphoneos");
    tmp.newFolder("foo3/usr/lib/swift_static/iphoneos");
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            createAppleSdk(),
            createAppleSdkPaths(
                developerDir,
                tmp.getRoot().resolve("foo"),
                tmp.getRoot().resolve("foo2"),
                tmp.getRoot().resolve("foo3")),
            swiftcTool,
            Optional.of(swiftStdTool),
            true,
            SwiftTargetTriple.of("x86_64", "apple", "ios", "9.3"));
    assertThat(swiftPlatform.getSwiftRuntimePathsForBundling(), hasSize(1));
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), hasSize(2));
  }
}
