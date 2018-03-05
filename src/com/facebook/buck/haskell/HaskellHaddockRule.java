/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class HaskellHaddockRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final Logger LOG = Logger.get(HaskellHaddockRule.class);

  @AddToRuleKey private final Tool haddockTool;

  @AddToRuleKey private final ImmutableList<String> flags;

  @AddToRuleKey private final ImmutableSet<SourcePath> interfaces;
  @AddToRuleKey private final ImmutableSet<SourcePath> outputDirs;

  private HaskellHaddockRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool haddockTool,
      ImmutableList<String> flags,
      ImmutableSet<SourcePath> interfaces,
      ImmutableSet<SourcePath> outputDirs) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.haddockTool = haddockTool;
    this.flags = flags;
    this.interfaces = interfaces;
    this.outputDirs = outputDirs;
  }

  public static HaskellHaddockRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      Tool haddockTool,
      ImmutableList<String> flags,
      ImmutableSet<HaskellHaddockInput> inputs) {
    ImmutableSet.Builder<SourcePath> ifacesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<SourcePath> outDirsBuilder = ImmutableSet.builder();
    for (HaskellHaddockInput i : inputs) {
      ifacesBuilder.addAll(i.getInterfaces());
      outDirsBuilder.addAll(i.getOutputDirs());
    }
    ImmutableSet<SourcePath> ifaces = ifacesBuilder.build();
    ImmutableSet<SourcePath> outDirs = outDirsBuilder.build();

    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(haddockTool, ruleFinder))
                    .addAll(ruleFinder.filterBuildRuleInputs(ifaces))
                    .addAll(ruleFinder.filterBuildRuleInputs(outDirs))
                    .build());
    return new HaskellHaddockRule(
        buildTarget,
        projectFilesystem,
        buildRuleParams.withDeclaredDeps(declaredDeps).withoutExtraDeps(),
        haddockTool,
        flags,
        ifaces,
        outDirs);
  }

  private Path getOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    SourcePathResolver resolver = context.getSourcePathResolver();
    String name = getBuildTarget().getShortName();
    Path dir = getOutputDir();

    LOG.info(name);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dir)));
    steps.add(new HaddockStep(getBuildTarget(), getProjectFilesystem().getRootPath(), context));

    // Copy the generated data from dependencies into our output directory
    for (SourcePath odir : outputDirs) {
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              resolver.getRelativePath(odir),
              dir,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }

    buildableContext.recordArtifact(dir);
    return steps.build();
  }

  private class HaddockStep extends ShellStep {

    private BuildContext buildContext;

    public HaddockStep(BuildTarget buildTarget, Path rootPath, BuildContext buildContext) {
      super(Optional.of(buildTarget), rootPath);
      this.buildContext = buildContext;
    }

    @Override
    protected boolean shouldPrintStderr(Verbosity verbosity) {
      return !verbosity.isSilent();
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      SourcePathResolver resolver = buildContext.getSourcePathResolver();
      return ImmutableList.<String>builder()
          .addAll(haddockTool.getCommandPrefix(resolver))
          .addAll(flags)
          .add("--gen-index")
          .add("--gen-contents")
          .addAll(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("--read-interface"),
                  RichStream.from(interfaces)
                      .map(sp -> resolver.getAbsolutePath(sp).toString())
                      .toImmutableList()))
          .add("-o", getOutputDir().resolve("HTML").toString())
          .build();
    }

    @Override
    public String getShortName() {
      return "haddock-build";
    }
  }
}
