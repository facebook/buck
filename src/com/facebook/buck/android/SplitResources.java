/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.resources.ExoResourcesRewriter;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class SplitResources extends AbstractBuildRule {
  @AddToRuleKey(stringify = true)
  private final Path exoResourcesOutputPath;

  @AddToRuleKey(stringify = true)
  private final Path primaryResourcesOutputPath;

  @AddToRuleKey(stringify = true)
  private final Path rDotTxtOutputPath;

  @AddToRuleKey private final SourcePath pathToAaptResources;
  @AddToRuleKey private final SourcePath pathToOriginalRDotTxt;

  public SplitResources(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToAaptResources,
      SourcePath pathToOriginalRDotTxt) {
    super(
        buildRuleParams.copyAppendingExtraDeps(
            getAllDeps(ruleFinder, pathToAaptResources, pathToOriginalRDotTxt)));
    this.pathToAaptResources = pathToAaptResources;
    this.pathToOriginalRDotTxt = pathToOriginalRDotTxt;
    this.exoResourcesOutputPath = getOutputDirectory().resolve("exo-resources.apk");
    this.primaryResourcesOutputPath = getOutputDirectory().resolve("primary-resources.apk");
    this.rDotTxtOutputPath = getOutputDirectory().resolve("R.txt");
  }

  private Path getOutputDirectory() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/");
  }

  private static ImmutableSet<BuildRule> getAllDeps(
      SourcePathRuleFinder ruleFinder, SourcePath aaptOutputPath, SourcePath aaptRDotTxtPath) {
    return ruleFinder.filterBuildRuleInputs(aaptOutputPath, aaptRDotTxtPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getOutputDirectory());
    return ImmutableList.<Step>builder()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getOutputDirectory()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getScratchDirectory()))
        .add(new SplitResourcesStep(context.getSourcePathResolver()))
        .add(
            new ZipalignStep(
                getProjectFilesystem().getRootPath(),
                getUnalignedExoPath(),
                exoResourcesOutputPath))
        .build();
  }

  private Path getUnalignedExoPath() {
    return getScratchDirectory().resolve("exo-resources.unaligned.zip");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  public SourcePath getPathToRDotTxt() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), rDotTxtOutputPath);
  }

  public SourcePath getPathToPrimaryResources() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), primaryResourcesOutputPath);
  }

  public SourcePath getPathToExoResources() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), exoResourcesOutputPath);
  }

  public Path getScratchDirectory() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s/");
  }

  private class SplitResourcesStep implements Step {
    private Path absolutePathToAaptResources;
    private Path absolutePathToOriginalRDotTxt;

    public SplitResourcesStep(SourcePathResolver sourcePathResolver) {
      absolutePathToAaptResources = sourcePathResolver.getAbsolutePath(pathToAaptResources);
      absolutePathToOriginalRDotTxt = sourcePathResolver.getAbsolutePath(pathToOriginalRDotTxt);
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      ExoResourcesRewriter.rewrite(
          absolutePathToAaptResources,
          absolutePathToOriginalRDotTxt,
          getProjectFilesystem().getPathForRelativePath(primaryResourcesOutputPath),
          getProjectFilesystem().getPathForRelativePath(getUnalignedExoPath()),
          getProjectFilesystem().getPathForRelativePath(rDotTxtOutputPath));
      return StepExecutionResult.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "splitting_exo_resources";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format(
          "split_exo_resources %s %s", absolutePathToAaptResources, absolutePathToOriginalRDotTxt);
    }
  }
}
