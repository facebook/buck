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
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;
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

  private final AndroidPlatformTarget androidPlatformTarget;
  private final SourcePathRuleFinder ruleFinder;

  public SplitResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToAaptResources,
      SourcePath pathToOriginalRDotTxt,
      AndroidPlatformTarget androidPlatformTarget) {
    super(buildTarget, projectFilesystem);
    this.androidPlatformTarget = androidPlatformTarget;
    this.ruleFinder = ruleFinder;
    this.pathToAaptResources = pathToAaptResources;
    this.pathToOriginalRDotTxt = pathToOriginalRDotTxt;
    this.exoResourcesOutputPath = getOutputDirectory().resolve("exo-resources.apk");
    this.primaryResourcesOutputPath = getOutputDirectory().resolve("primary-resources.apk");
    this.rDotTxtOutputPath = getOutputDirectory().resolve("R.txt");
  }

  private Path getOutputDirectory() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/");
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return BuildableSupport.deriveDeps(this, ruleFinder)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getOutputDirectory());

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), getOutputDirectory())))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), getScratchDirectory())))
        .add(new SplitResourcesStep(context.getSourcePathResolver()))
        .add(
            new ZipalignStep(
                getProjectFilesystem().getRootPath(),
                androidPlatformTarget,
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
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), rDotTxtOutputPath);
  }

  public SourcePath getPathToPrimaryResources() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), primaryResourcesOutputPath);
  }

  public SourcePath getPathToExoResources() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), exoResourcesOutputPath);
  }

  public Path getScratchDirectory() {
    return BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s/");
  }

  private class SplitResourcesStep implements Step {
    private Path absolutePathToAaptResources;
    private Path absolutePathToOriginalRDotTxt;

    public SplitResourcesStep(SourcePathResolver sourcePathResolver) {
      absolutePathToAaptResources = sourcePathResolver.getAbsolutePath(pathToAaptResources);
      absolutePathToOriginalRDotTxt = sourcePathResolver.getAbsolutePath(pathToOriginalRDotTxt);
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      ExoResourcesRewriter.rewrite(
          absolutePathToAaptResources,
          absolutePathToOriginalRDotTxt,
          getProjectFilesystem().getPathForRelativePath(primaryResourcesOutputPath),
          getProjectFilesystem().getPathForRelativePath(getUnalignedExoPath()),
          getProjectFilesystem().getPathForRelativePath(rDotTxtOutputPath));
      return StepExecutionResults.SUCCESS;
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
