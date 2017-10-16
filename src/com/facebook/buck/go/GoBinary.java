/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

public class GoBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps implements BinaryBuildRule {

  @AddToRuleKey private final Tool linker;
  @AddToRuleKey private final ImmutableList<String> linkerFlags;
  @AddToRuleKey private final Optional<Linker> cxxLinker;
  @AddToRuleKey private final GoPlatform platform;

  private final GoCompile mainObject;
  private final SymlinkTree linkTree;

  private final Path output;

  public GoBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<Linker> cxxLinker,
      SymlinkTree linkTree,
      GoCompile mainObject,
      Tool linker,
      ImmutableList<String> linkerFlags,
      GoPlatform platform) {
    super(buildTarget, projectFilesystem, params);
    this.cxxLinker = cxxLinker;
    this.linker = linker;
    this.linkTree = linkTree;
    this.mainObject = mainObject;
    this.platform = platform;
    this.output =
        BuildTargets.getGenPath(
            getProjectFilesystem(), buildTarget, "%s/" + buildTarget.getShortName());
    this.linkerFlags = linkerFlags;
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder().addArg(SourcePathArg.of(getSourcePathToOutput())).build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

    // There is no way to specify real-ld environment variables to the go linker - just hope
    // that the two sets don't collide.
    ImmutableList<String> cxxLinkerCommand = ImmutableList.of();
    ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
    if (cxxLinker.isPresent()) {
      environment.putAll(cxxLinker.get().getEnvironment(context.getSourcePathResolver()));
      cxxLinkerCommand = cxxLinker.get().getCommandPrefix(context.getSourcePathResolver());
    }
    environment.putAll(linker.getEnvironment(context.getSourcePathResolver()));
    return ImmutableList.of(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())),
        new GoLinkStep(
            getBuildTarget(),
            getProjectFilesystem().getRootPath(),
            environment.build(),
            cxxLinkerCommand,
            linker.getCommandPrefix(context.getSourcePathResolver()),
            linkerFlags,
            ImmutableList.of(linkTree.getRoot()),
            platform,
            context.getSourcePathResolver().getRelativePath(mainObject.getSourcePathToOutput()),
            GoLinkStep.LinkMode.EXECUTABLE,
            output));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
