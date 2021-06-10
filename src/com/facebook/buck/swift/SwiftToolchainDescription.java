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
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

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
    CommandTool.Builder swiftcBuilder =
        new CommandTool.Builder(Tools.resolveTool(args.getSwiftc(), actionGraphBuilder));
    // The frontend flag is mandatory and has to come first. Keep that logic internal until we
    // migrate the SwiftCompile logic to use the driver.
    swiftcBuilder.addArg("-frontend");
    Tool swiftc = swiftcBuilder.build();

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            context.getActionGraphBuilder(),
            ImmutableList.of(LocationMacroExpander.INSTANCE),
            Optional.empty());

    ImmutableList<Arg> swiftFlags =
        args.getSwiftcFlags().stream()
            .map(macrosConverter::convert)
            .collect(ImmutableList.toImmutableList());

    Optional<Tool> swiftStdlibTool =
        args.getSwiftStdlibTool().map(path -> Tools.resolveTool(path, actionGraphBuilder));
    if (swiftStdlibTool.isPresent() && !args.getSwiftStdlibToolFlags().isEmpty()) {
      CommandTool.Builder swiftStdlibToolBuilder = new CommandTool.Builder(swiftStdlibTool.get());
      args.getSwiftStdlibToolFlags()
          .forEach(a -> swiftStdlibToolBuilder.addArg(macrosConverter.convert(a)));
      swiftStdlibTool = Optional.of(swiftStdlibToolBuilder.build());
    }
    return new SwiftToolchainBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        swiftc,
        swiftFlags,
        swiftStdlibTool,
        args.getRuntimePathsForBundling().stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList()),
        args.getRuntimePathsForLinking().stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList()),
        args.getStaticRuntimePaths().stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList()),
        args.getRuntimeRunPaths().stream().map(Paths::get).collect(ImmutableList.toImmutableList()),
        args.getPrefixSerializedDebugInfo());
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
    ImmutableList<StringWithMacros> getSwiftcFlags();

    /** Swift stdlib tool binary. */
    Optional<SourcePath> getSwiftStdlibTool();

    /** Flags for Swift stdlib tool. */
    ImmutableList<StringWithMacros> getSwiftStdlibToolFlags();

    /** Runtime paths for bundling. */
    ImmutableList<String> getRuntimePathsForBundling();

    /** Runtime paths for linking. */
    ImmutableList<String> getRuntimePathsForLinking();

    /** Static runtime paths. */
    ImmutableList<String> getStaticRuntimePaths();

    /** Runtime run paths. */
    ImmutableList<String> getRuntimeRunPaths();

    /** If the toolchain supports the -prefix-serialized-debug-info flag. */
    @Value.Default
    default boolean getPrefixSerializedDebugInfo() {
      return false;
    }
  }
}
