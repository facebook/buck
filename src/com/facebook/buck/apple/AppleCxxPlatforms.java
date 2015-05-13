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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.Tool;
import com.facebook.buck.cxx.VersionedTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to
 * support building iOS and Mac OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() { }

  private static final Path USR_BIN = Paths.get("usr/bin");

  public static AppleCxxPlatform build(
      AppleSdk targetSdk,
      String targetVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig) {
    return buildWithExecutableChecker(
        targetSdk,
        targetVersion,
        targetArchitecture,
        sdkPaths,
        buckConfig,
        new ExecutableFinder());
  }

  @VisibleForTesting
  static AppleCxxPlatform buildWithExecutableChecker(
      AppleSdk targetSdk,
      String targetVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<Path> toolSearchPathsBuilder = ImmutableList.builder();
    // Search for tools from most specific to least specific.
    toolSearchPathsBuilder
        .add(sdkPaths.getSdkPath().resolve(USR_BIN))
        .add(sdkPaths.getPlatformPath().resolve("Developer").resolve(USR_BIN));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      toolSearchPathsBuilder.add(toolchainPath.resolve(USR_BIN));
    }
    if (sdkPaths.getDeveloperPath().isPresent()) {
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve(USR_BIN));
    }
    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    switch (targetSdk.getApplePlatform().getName()) {
      case ApplePlatform.Name.IPHONEOS:
        cflagsBuilder.add("-mios-version-min=" + targetVersion);
        break;
      case ApplePlatform.Name.IPHONESIMULATOR:
        cflagsBuilder.add("-mios-simulator-version-min=" + targetVersion);
        break;
      default:
        // For Mac builds, -mmacosx-version-min=<version>.
        cflagsBuilder.add(
            "-m" + targetSdk.getApplePlatform().getName() + "-version-min=" + targetVersion);
        break;
    }
    // TODO(user): Add more and better cflags.
    ImmutableList<String> cflags = cflagsBuilder.build();

    ImmutableList.Builder<String> versionsBuilder = ImmutableList.builder();
    versionsBuilder.add(targetSdk.getVersion());
    for (AppleToolchain toolchain : targetSdk.getToolchains()) {
      versionsBuilder.add(toolchain.getVersion());
    }
    String version = Joiner.on(':').join(versionsBuilder.build());

    Tool clangPath = new VersionedTool(
        getToolPath("clang", toolSearchPaths, executableFinder),
        cflags,
        "apple-clang",
        version);

    Tool clangXxPath = new VersionedTool(
        getToolPath("clang++", toolSearchPaths, executableFinder),
        cflags,
        "apple-clang++",
        version);

    Tool ar = new VersionedTool(
        getToolPath("ar", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-ar",
        version);

    Tool actool = new VersionedTool(
        getToolPath("actool", toolSearchPaths, executableFinder),
        ImmutableList.<String>of(),
        "apple-actool",
        version);

    CxxBuckConfig config = new CxxBuckConfig(buckConfig);

    ImmutableFlavor targetFlavor = ImmutableFlavor.of(
        ImmutableFlavor.replaceInvalidCharacters(
            targetSdk.getName() + "-" + targetArchitecture));

    CxxPlatform cxxPlatform = CxxPlatforms.build(
        targetFlavor,
        Platform.MACOS,
        config,
        clangPath,
        clangPath,
        clangPath,
        clangXxPath,
        clangPath,
        clangXxPath,
        clangXxPath,
        Optional.of(CxxPlatform.LinkerType.DARWIN),
        clangXxPath,
        ar,
        "!<arch>\n".getBytes(Charsets.US_ASCII),
        getOptionalTool("lex", toolSearchPaths, executableFinder, version),
        getOptionalTool("yacc", toolSearchPaths, executableFinder, version));

    return AppleCxxPlatform.builder()
        .setCxxPlatform(cxxPlatform)
        .setApplePlatform(targetSdk.getApplePlatform())
        .setAppleSdkPaths(sdkPaths)
        .setActool(actool)
        .build();
  }

  private static Optional<Tool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder,
      String version) {
    return getOptionalToolPath(tool, toolSearchPaths, executableFinder)
        .transform(VersionedTool.fromPath(tool, version))
        .transform(Functions.<Tool>identity());
  }

  private static Path getToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {
    Optional<Path> result = getOptionalToolPath(tool, toolSearchPaths, executableFinder);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

    private static Optional<Path> getOptionalToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      ExecutableFinder executableFinder) {

      return executableFinder.getOptionalExecutable(
          Paths.get(tool),
          toolSearchPaths,
          ImmutableSet.<String>of());
  }

}
