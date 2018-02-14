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

package com.facebook.buck.android.aapt;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;

public class MergeAndroidResourceSources extends AbstractBuildRule {

  private final Path destinationDirectory;
  private final Path tempDirectory;
  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  @AddToRuleKey private final ImmutableCollection<SourcePath> originalDirectories;

  public MergeAndroidResourceSources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableCollection<SourcePath> directories) {
    super(buildTarget, projectFilesystem);
    this.originalDirectories = directories;
    this.destinationDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "__merged_resources_%s__");
    this.tempDirectory =
        BuildTargets.getScratchPath(
            getProjectFilesystem(), buildTarget, "__merged_resources_%s_tmp__");
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), destinationDirectory)));
    steps.add(
        MergeAndroidResourceSourcesStep.builder()
            .setResPaths(
                originalDirectories
                    .stream()
                    .map(context.getSourcePathResolver()::getAbsolutePath)
                    .collect(ImmutableList.toImmutableList()))
            .setOutFolderPath(getProjectFilesystem().resolve(destinationDirectory))
            .setTmpFolderPath(getProjectFilesystem().resolve(tempDirectory))
            .build());
    buildableContext.recordArtifact(destinationDirectory);
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), destinationDirectory);
  }
}
