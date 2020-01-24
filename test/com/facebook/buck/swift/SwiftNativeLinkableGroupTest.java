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

package com.facebook.buck.swift;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftTargetTriple;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftNativeLinkableGroupTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Tool swiftcTool;
  private Tool swiftStdTool;
  private SourcePathResolverAdapter sourcePathResolverAdapter;
  private AppleSdk iphoneSdk;
  private AppleSdkPaths iphoneSdkPaths;
  private AppleSdk macosxSdk;
  private AppleSdkPaths macosxSdkPaths;

  private void setUpAppleSdks() {
    Path developerDir;
    try {
      developerDir = tmp.newFolder("Developer");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    iphoneSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of())
            .build();
    iphoneSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();
    macosxSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.MACOSX)
            .setName("macosx10.14")
            .setVersion("10.14")
            .setToolchains(ImmutableList.of())
            .build();
    macosxSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .setPlatformPath(developerDir.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.14sdk"))
            .build();
  }

  @Before
  public void setUp() {
    swiftcTool = VersionedTool.of("foo", FakeSourcePath.of("swiftc"), "1.0");
    swiftStdTool = VersionedTool.of("foo", FakeSourcePath.of("swift-std"), "1.0");

    setUpAppleSdks();

    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolverAdapter = buildRuleResolver.getSourcePathResolver();
  }

  @Test
  public void testStaticLinkerFlagsOnMobile() {
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            iphoneSdk,
            iphoneSdkPaths,
            swiftcTool,
            Optional.of(swiftStdTool),
            true,
            SwiftTargetTriple.of("x86_64", "apple", "ios", "9.3"));

    ImmutableList.Builder<Arg> staticArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkableGroup.populateLinkerArguments(
        staticArgsBuilder, swiftPlatform, Linker.LinkableDepType.STATIC);

    ImmutableList.Builder<Arg> sharedArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkableGroup.populateLinkerArguments(
        sharedArgsBuilder, swiftPlatform, Linker.LinkableDepType.SHARED);

    ImmutableList<Arg> staticArgs = staticArgsBuilder.build();
    ImmutableList<Arg> sharedArgs = sharedArgsBuilder.build();

    // On iOS, Swift runtime is not available as static libs
    assertEquals(staticArgs, sharedArgs);
    assertEquals(
        Arg.stringify(sharedArgs, sourcePathResolverAdapter),
        ImmutableList.of(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("/usr/lib/swift"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@executable_path/Frameworks"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@loader_path/Frameworks")));
  }

  @Test
  public void testStaticLinkerFlagsOnMac() {
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            macosxSdk,
            macosxSdkPaths,
            swiftcTool,
            Optional.of(swiftStdTool),
            true,
            SwiftTargetTriple.of("x86_64", "apple", "ios", "9.3"));

    ImmutableList.Builder<Arg> sharedArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkableGroup.populateLinkerArguments(
        sharedArgsBuilder, swiftPlatform, Linker.LinkableDepType.SHARED);

    ImmutableList<Arg> sharedArgs = sharedArgsBuilder.build();
    assertEquals(
        Arg.stringify(sharedArgs, sourcePathResolverAdapter),
        ImmutableList.of(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("/usr/lib/swift"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@executable_path/../Frameworks"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@loader_path/../Frameworks")));
  }
}
