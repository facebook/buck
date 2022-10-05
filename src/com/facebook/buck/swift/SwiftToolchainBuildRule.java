/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * This {@link BuildRule} holds tools and flags to create {@link SwiftPlatform}. It's a {@link
 * NoopBuildRule} with no build steps or outputs.
 */
public class SwiftToolchainBuildRule extends NoopBuildRule {

  private final Tool swiftc;
  private final ImmutableList<Arg> swiftFlags;
  private final Optional<Tool> swiftStdlibTool;
  private final SourcePath platformPath;
  private final SourcePath sdkPath;
  private final Optional<SourcePath> resourceDir;
  private final Optional<String> sdkDependenciesPath;
  private final ImmutableList<Path> runtimePathsForBundling;
  private final ImmutableList<Path> runtimePathsForLinking;
  private final ImmutableList<Path> staticRuntimePaths;
  private final ImmutableList<Path> runtimeRunPaths;
  private final boolean prefixSerializedDebuggingOptions;
  private final boolean canToolchainEmitObjCHeaderTextually;
  private final boolean explicitModulesUsesGmodules;

  public SwiftToolchainBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Tool swiftc,
      ImmutableList<Arg> swiftFlags,
      Optional<Tool> swiftStdlibTool,
      SourcePath platformPath,
      SourcePath sdkPath,
      Optional<SourcePath> resourceDir,
      Optional<String> sdkDependenciesPath,
      ImmutableList<Path> runtimePathsForBundling,
      ImmutableList<Path> runtimePathsForLinking,
      ImmutableList<Path> staticRuntimePaths,
      ImmutableList<Path> runtimeRunPaths,
      boolean prefixSerializedDebuggingOptions,
      boolean canToolchainEmitObjCHeaderTextually,
      boolean explicitModulesUsesGmodules) {
    super(buildTarget, projectFilesystem);
    this.swiftc = swiftc;
    this.swiftFlags = swiftFlags;
    this.swiftStdlibTool = swiftStdlibTool;
    this.platformPath = platformPath;
    this.sdkPath = sdkPath;
    this.resourceDir = resourceDir;
    this.sdkDependenciesPath = sdkDependenciesPath;
    this.runtimePathsForBundling = runtimePathsForBundling;
    this.runtimePathsForLinking = runtimePathsForLinking;
    this.staticRuntimePaths = staticRuntimePaths;
    this.runtimeRunPaths = runtimeRunPaths;
    this.prefixSerializedDebuggingOptions = prefixSerializedDebuggingOptions;
    this.canToolchainEmitObjCHeaderTextually = canToolchainEmitObjCHeaderTextually;
    this.explicitModulesUsesGmodules = explicitModulesUsesGmodules;
  }

  /** Provides SwiftPlatform for given Swift target triple */
  public SwiftPlatform getSwiftPlatform(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      AppleCompilerTargetTriple swiftTarget) {
    return SwiftPlatform.builder()
        .setSwiftc(swiftc)
        .setSwiftFlags(swiftFlags)
        .setSwiftStdlibTool(swiftStdlibTool)
        .setPlatformPath(platformPath)
        .setSdkPath(sdkPath)
        .setResourceDir(resourceDir)
        .setSdkDependencies(getSdkDependencies(graphBuilder, projectFilesystem))
        .setSwiftTarget(swiftTarget)
        .setSwiftRuntimePathsForBundling(runtimePathsForBundling)
        .setSwiftRuntimePathsForLinking(runtimePathsForLinking)
        .setSwiftStaticRuntimePaths(staticRuntimePaths)
        .setSwiftSharedLibraryRunPaths(runtimeRunPaths)
        .setDebugPrefixMap(ImmutableBiMap.of())
        .setPrefixSerializedDebuggingOptions(prefixSerializedDebuggingOptions)
        .setCanToolchainEmitObjCHeaderTextually(canToolchainEmitObjCHeaderTextually)
        .setExplicitModulesUsesGmodules(explicitModulesUsesGmodules)
        .build();
  }

  private Optional<SwiftSdkDependencies> getSdkDependencies(
      ActionGraphBuilder graphBuilder, ProjectFilesystem projectFilesystem) {
    if (sdkDependenciesPath.isEmpty()) {
      return Optional.empty();
    }
    if (resourceDir.isEmpty()) {
      throw new HumanReadableException(
          "swift_toolchain has sdk_dependencies_path but is missing resource_dir");
    }

    // We use a minimal set of Swift flags for 2 reasons:
    // 1. these will be passed directly to frontend invocations, so we cannot pass through all
    //    driver args.
    // 2. the swift interface compilation will ignore flags anyway.
    ImmutableList.Builder<Arg> swiftFlagsBuilder = ImmutableList.builder();
    swiftFlagsBuilder.add(
        StringArg.of("-sdk"),
        SourcePathArg.of(sdkPath),
        StringArg.of("-resource-dir"),
        SourcePathArg.of(resourceDir.get()));

    return Optional.of(
        new SwiftSdkDependencies(
            graphBuilder,
            projectFilesystem,
            sdkDependenciesPath.get(),
            swiftc,
            swiftFlagsBuilder.build(),
            sdkPath,
            platformPath,
            resourceDir.get(),
            getBuildTarget(),
            explicitModulesUsesGmodules));
  }
}
