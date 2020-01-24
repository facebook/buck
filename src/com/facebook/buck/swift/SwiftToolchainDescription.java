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

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** Defines an swift_toolchain rule which provides values to fill {@link SwiftPlatform}. */
public class SwiftToolchainDescription
    implements DescriptionWithTargetGraph<SwiftToolchainDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SwiftToolchainDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());
    ActionGraphBuilder actionGraphBuilder = context.getActionGraphBuilder();
    Tool swiftc = Tools.resolveTool(args.getSwiftc(), actionGraphBuilder);
    if (!args.getSwiftcFlags().isEmpty()) {
      CommandTool.Builder swiftcBuilder = new CommandTool.Builder(swiftc);
      args.getSwiftcFlags().forEach(swiftcBuilder::addArg);
      swiftc = swiftcBuilder.build();
    }
    Optional<Tool> swiftStdlibTool =
        args.getSwiftStdlibTool().map(path -> Tools.resolveTool(path, actionGraphBuilder));
    if (swiftStdlibTool.isPresent() && !args.getSwiftStdlibToolFlags().isEmpty()) {
      CommandTool.Builder swiftStdlibToolBuilder = new CommandTool.Builder(swiftStdlibTool.get());
      args.getSwiftStdlibToolFlags().forEach(swiftStdlibToolBuilder::addArg);
      swiftStdlibTool = Optional.of(swiftStdlibToolBuilder.build());
    }
    SourcePathResolverAdapter pathResolver = actionGraphBuilder.getSourcePathResolver();
    return new SwiftToolchainBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        swiftc,
        swiftStdlibTool,
        args.getRuntimePathsForBundling().stream()
            .map(pathResolver::getAbsolutePath)
            .collect(ImmutableList.toImmutableList()),
        args.getRuntimePathsForLinking().stream()
            .map(pathResolver::getAbsolutePath)
            .collect(ImmutableList.toImmutableList()),
        args.getStaticRuntimePaths().stream()
            .map(pathResolver::getAbsolutePath)
            .collect(ImmutableList.toImmutableList()),
        args.getRuntimeRunPaths().stream()
            .map(pathResolver::getAbsolutePath)
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public Class<SwiftToolchainDescriptionArg> getConstructorArgType() {
    return SwiftToolchainDescriptionArg.class;
  }

  /**
   * swift_toolchain defines swiftc and swift-stdlib-tool with their flags to construct
   * SwiftPlatform.
   */
  @RuleArg
  interface AbstractSwiftToolchainDescriptionArg extends BuildRuleArg {

    /** Swift compiler binary. */
    SourcePath getSwiftc();

    /** Flags for Swift compiler. */
    ImmutableList<String> getSwiftcFlags();

    /** Swift stdlib tool binary. */
    Optional<SourcePath> getSwiftStdlibTool();

    /** Flags for Swift stdlib tool. */
    ImmutableList<String> getSwiftStdlibToolFlags();

    /** Runtime paths for bundling. */
    ImmutableList<SourcePath> getRuntimePathsForBundling();

    /** Runtime paths for linking. */
    ImmutableList<SourcePath> getRuntimePathsForLinking();

    /** Static runtime paths. */
    ImmutableList<SourcePath> getStaticRuntimePaths();

    /** Runtime run paths. */
    ImmutableList<SourcePath> getRuntimeRunPaths();
  }
}
