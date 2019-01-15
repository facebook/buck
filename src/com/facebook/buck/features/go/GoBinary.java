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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.CxxPrepareForLinkStep;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public class GoBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps implements BinaryBuildRule {

  @AddToRuleKey private final Tool linker;
  @AddToRuleKey private final Linker cxxLinker;
  @AddToRuleKey private final ImmutableList<String> linkerFlags;
  @AddToRuleKey private final ImmutableList<Arg> cxxLinkerArgs;
  @AddToRuleKey private final GoLinkStep.LinkMode linkMode;
  @AddToRuleKey private final GoPlatform platform;

  private final Path output;
  private final GoCompile mainObject;
  private final SymlinkTree linkTree;
  private final ImmutableSortedSet<SourcePath> resources;

  public GoBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> resources,
      SymlinkTree linkTree,
      GoCompile mainObject,
      Tool linker,
      Linker cxxLinker,
      GoLinkStep.LinkMode linkMode,
      ImmutableList<String> linkerFlags,
      ImmutableList<Arg> cxxLinkerArgs,
      GoPlatform platform) {
    super(buildTarget, projectFilesystem, params);
    this.cxxLinker = cxxLinker;
    this.cxxLinkerArgs = cxxLinkerArgs;
    this.resources = resources;
    this.linker = linker;
    this.linkTree = linkTree;
    this.mainObject = mainObject;
    this.platform = platform;
    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), buildTarget, "%s/" + buildTarget.getShortName());
    this.linkerFlags = linkerFlags;
    this.linkMode = linkMode;
  }

  private SymlinkTreeStep getResourceSymlinkTree(
      BuildContext buildContext, Path outputDirectory, BuildableContext buildableContext) {

    SourcePathResolver resolver = buildContext.getSourcePathResolver();

    resources.forEach(
        pth ->
            buildableContext.recordArtifact(resolver.getRelativePath(getProjectFilesystem(), pth)));

    return new SymlinkTreeStep(
        "go_binary",
        getProjectFilesystem(),
        outputDirectory,
        resources
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    input ->
                        getProjectFilesystem()
                            .getPath(resolver.getSourcePathName(getBuildTarget(), input)),
                    input -> resolver.getAbsolutePath(input))));
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder().addArg(SourcePathArg.of(getSourcePathToOutput())).build();
  }

  private ImmutableMap<String, String> getEnvironment(BuildContext context) {
    ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();

    if (linkMode == GoLinkStep.LinkMode.EXTERNAL) {
      environment.putAll(cxxLinker.getEnvironment(context.getSourcePathResolver()));
    }
    environment.putAll(linker.getEnvironment(context.getSourcePathResolver()));

    return environment.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

    SourcePathResolver resolver = context.getSourcePathResolver();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    // symlink resources to target directory
    steps.addAll(
        ImmutableList.of(getResourceSymlinkTree(context, output.getParent(), buildableContext)));

    // cxxLinkerArgs comes from cgo rules and are reuqired for cxx deps linking
    ImmutableList.Builder<String> externalLinkerFlags = ImmutableList.builder();
    if (linkMode == GoLinkStep.LinkMode.EXTERNAL) {
      Path argFilePath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargetPaths.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s.argsfile"));
      Path fileListPath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargetPaths.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt"));
      steps.addAll(
          CxxPrepareForLinkStep.create(
              argFilePath,
              fileListPath,
              cxxLinker.fileList(fileListPath),
              output,
              cxxLinkerArgs,
              cxxLinker,
              getBuildTarget().getCellPath(),
              resolver));
      externalLinkerFlags.add(String.format("@%s", argFilePath));
    }

    steps.add(
        new GoLinkStep(
            getProjectFilesystem().getRootPath(),
            getEnvironment(context),
            cxxLinker.getCommandPrefix(resolver),
            linker.getCommandPrefix(resolver),
            linkerFlags,
            externalLinkerFlags.build(),
            ImmutableList.of(linkTree.getRoot()),
            platform,
            resolver.getRelativePath(mainObject.getSourcePathToOutput()),
            GoLinkStep.BuildMode.EXECUTABLE,
            linkMode,
            output));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
