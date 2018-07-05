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
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Optional;

public class GoBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps implements BinaryBuildRule {

  @AddToRuleKey private final Tool linker;
  @AddToRuleKey private final ImmutableList<String> linkerFlags;
  @AddToRuleKey private final Optional<Linker> cxxLinker;
  @AddToRuleKey private final ImmutableList<Arg> cxxLinkerArgs;
  @AddToRuleKey private final GoPlatform platform;

  private final Path output;
  private final GoCompile mainObject;
  private final SymlinkTree linkTree;
  private final ImmutableSortedSet<SourcePath> resources;

  public GoBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<Linker> cxxLinker,
      ImmutableList<Arg> cxxLinkerArgs,
      ImmutableSortedSet<SourcePath> resources,
      SymlinkTree linkTree,
      GoCompile mainObject,
      Tool linker,
      ImmutableList<String> linkerFlags,
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
        BuildTargets.getGenPath(
            getProjectFilesystem(), buildTarget, "%s/" + buildTarget.getShortName());
    this.linkerFlags = linkerFlags;
  }

  private SymlinkTreeStep getResourceSymlinkTree(
      BuildContext buildContext, Path outputDirectory, BuildableContext buildableContext) {

    resources.forEach(
        pth ->
            buildableContext.recordArtifact(
                buildContext.getSourcePathResolver().getRelativePath(getProjectFilesystem(), pth)));

    return new SymlinkTreeStep(
        "go_binary",
        getProjectFilesystem(),
        outputDirectory,
        ImmutableMap.copyOf(
            FluentIterable.from(resources)
                .transform(
                    input ->
                        Maps.immutableEntry(
                            getProjectFilesystem()
                                .getPath(
                                    buildContext
                                        .getSourcePathResolver()
                                        .getSourcePathName(getBuildTarget(), input)),
                            buildContext.getSourcePathResolver().getAbsolutePath(input)))));
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
    ImmutableList.Builder<String> allLinkerFlags = ImmutableList.builder();
    allLinkerFlags.addAll(linkerFlags);
    if (cxxLinkerArgs.size() > 0) {
      Path argFilePath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargets.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s.argsfile"));
      Path fileListPath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargets.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt"));
      steps.addAll(
          CxxPrepareForLinkStep.create(
              argFilePath,
              fileListPath,
              cxxLinker.get().fileList(fileListPath),
              output,
              cxxLinkerArgs,
              cxxLinker.get(),
              getBuildTarget().getCellPath(),
              resolver));
      allLinkerFlags.addAll(ImmutableList.of("-extldflags", String.format("@%s", argFilePath)));
    }

    steps.add(
        new GoLinkStep(
            getBuildTarget(),
            getProjectFilesystem().getRootPath(),
            environment.build(),
            cxxLinkerCommand,
            linker.getCommandPrefix(context.getSourcePathResolver()),
            allLinkerFlags.build(),
            ImmutableList.of(linkTree.getRoot()),
            platform,
            context.getSourcePathResolver().getRelativePath(mainObject.getSourcePathToOutput()),
            GoLinkStep.LinkMode.EXECUTABLE,
            output));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
