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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

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

  public static CxxPlatform build(
      ApplePlatform targetPlatform,
      String targetSdkName,
      String xcodeVersion,
      String targetVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig) {
    return buildWithExecutableChecker(
        targetPlatform,
        targetSdkName,
        xcodeVersion,
        targetVersion,
        targetArchitecture,
        sdkPaths,
        buckConfig,
        MorePaths.DEFAULT_PATH_IS_EXECUTABLE_CHECKER);
  }

  @VisibleForTesting
  static CxxPlatform buildWithExecutableChecker(
      ApplePlatform targetPlatform,
      String targetSdkName,
      String xcodeVersion,
      String targetVersion,
      String targetArchitecture,
      AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      Function<Path, Boolean> pathIsExecutableChecker) {

    ImmutableList.Builder<Path> toolSearchPathsBuilder = ImmutableList.builder();
    // Search for tools from most specific to least specific.
    toolSearchPathsBuilder
        .add(sdkPaths.getSdkPath().resolve(USR_BIN))
        .add(sdkPaths.getPlatformPath().resolve("Developer").resolve(USR_BIN));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      toolSearchPathsBuilder.add(toolchainPath.resolve(USR_BIN));
    }
    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    switch (targetPlatform) {
      case MACOSX:
        cflagsBuilder.add("-mmacosx-version-min=" + targetVersion);
        break;
      case IPHONESIMULATOR:
        cflagsBuilder.add("-mios-simulator-version-min=" + targetVersion);
        break;
      case IPHONEOS:
        cflagsBuilder.add("-mios-version-min=" + targetVersion);
        break;
    }
    // TODO(user): Add more and better cflags.
    ImmutableList<String> cflags = cflagsBuilder.build();

    String xcodeAndSdkVersion = Joiner.on(':').join(
        xcodeVersion,
        targetSdkName);

    Tool clangPath = new VersionedTool(
        getToolPath("clang", toolSearchPaths, pathIsExecutableChecker),
        cflags,
        "apple-clang",
        xcodeAndSdkVersion);

    Tool clangXxPath = new VersionedTool(
        getToolPath("clang++", toolSearchPaths, pathIsExecutableChecker),
        cflags,
        "apple-clang++",
        xcodeAndSdkVersion);

    Tool libtool = new VersionedTool(
        getToolPath("libtool", toolSearchPaths, pathIsExecutableChecker),
        ImmutableList.<String>of(),
        "apple-libtool",
        xcodeAndSdkVersion);

    Tool ar = new VersionedTool(
        getToolPath("ar", toolSearchPaths, pathIsExecutableChecker),
        ImmutableList.<String>of(),
        "apple-ar",
        xcodeAndSdkVersion);

    CxxBuckConfig config = new CxxBuckConfig(buckConfig);

    return CxxPlatforms.build(
        ImmutableFlavor.of(targetSdkName + "-" + targetArchitecture),
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
        libtool,
        ar,
        getOptionalTool("lex", toolSearchPaths, pathIsExecutableChecker, xcodeAndSdkVersion),
        getOptionalTool("yacc", toolSearchPaths, pathIsExecutableChecker, xcodeAndSdkVersion));
  }

  private static Optional<Path> getOptionalToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      Function<Path, Boolean> pathIsExecutableChecker) {
    return MorePaths.searchPathsForExecutable(
        Paths.get(tool),
        toolSearchPaths,
        ImmutableList.<String>of(),
        pathIsExecutableChecker);
  }

  private static Optional<Tool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      Function<Path, Boolean> pathIsExecutableChecker,
      String version) {
    return getOptionalToolPath(tool, toolSearchPaths, pathIsExecutableChecker)
        .transform(VersionedTool.fromPath(tool, version))
        .transform(Functions.<Tool>identity());
  }

  private static Path getToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      Function<Path, Boolean> pathIsExecutableChecker) {
    Optional<Path> result = getOptionalToolPath(tool, toolSearchPaths, pathIsExecutableChecker);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

}
