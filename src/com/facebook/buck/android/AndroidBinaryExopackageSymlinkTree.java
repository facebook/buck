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

package com.facebook.buck.android;

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Create an exopackage symlink tree for Android binaries rules */
public class AndroidBinaryExopackageSymlinkTree extends AbstractBuildRule {
  @AddToRuleKey private final SourcePath manifestPath;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> exoSourcePaths;

  private final ExopackageInfo exopackageInfo;
  private final Supplier<ImmutableSortedSet<BuildRule>> depsSupplier;

  protected AndroidBinaryExopackageSymlinkTree(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      ExopackageInfo exopackageInfo,
      SourcePath manifestPath) {
    super(buildTarget, projectFilesystem);
    this.exopackageInfo = exopackageInfo;
    this.manifestPath = manifestPath;
    this.exoSourcePaths = getExopackageSourcePaths(exopackageInfo);
    this.depsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(sourcePathRuleFinder.filterBuildRuleInputs(exoSourcePaths))
                    .addAll(sourcePathRuleFinder.filterBuildRuleInputs(manifestPath))
                    .build());
  }

  private static ImmutableSortedSet<SourcePath> getExopackageSourcePaths(ExopackageInfo exoInfo) {
    return exoInfo
        .getRequiredPaths()
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getOutputPath());
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        new AbstractExecutionStep("creating_exopackage_symlink_tree") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            ExopackageSymlinkTreeStep.createExopackageDirForExopackageInfo(
                exopackageInfo,
                getProjectFilesystem(),
                getBuildTarget(),
                buildContext.getSourcePathResolver(),
                manifestPath);
            return StepExecutionResults.SUCCESS;
          }
        });
    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputPath());
  }

  private Path getOutputPath() {
    return ExopackageSymlinkTreeStep.getExopackageSymlinkTreePath(
        getBuildTarget(), getProjectFilesystem());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }
}
