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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class MergeAssets extends AbstractBuildRule {
  @AddToRuleKey
  private final ImmutableSet<SourcePath> assetsDirectories;

  public MergeAssets(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> assetsDirectories) {
    super(buildRuleParams
        .appendExtraDeps(
        ImmutableSortedSet.copyOf(
            ruleFinder.filterBuildRuleInputs(assetsDirectories))));
    this.assetsDirectories = assetsDirectories;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), getScratchDirectory()));
    steps.add(new MkdirStep(getProjectFilesystem(), getScratchAssetsDirectory()));
    steps.add(new MkdirStep(getProjectFilesystem(), getPathToMergedAssets().getParent()));
    for (SourcePath path : assetsDirectories) {
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              context.getSourcePathResolver().getRelativePath(path),
              getScratchAssetsDirectory(),
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }
    steps.add(new RmStep(getProjectFilesystem(), getPathToMergedAssets()));
    steps.add(new ZipStep(
        getProjectFilesystem(),
        getPathToMergedAssets(),
        ImmutableSet.of(),
        false,
        ZipCompressionLevel.MIN_COMPRESSION_LEVEL,
        getScratchDirectory()));
    buildableContext.recordArtifact(getPathToMergedAssets());
    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToMergedAssets());
  }

  private Path getScratchDirectory() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private Path getScratchAssetsDirectory() {
    return getScratchDirectory().resolve("assets");
  }

  public Path getPathToMergedAssets() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s.merged.assets.zip");
  }
}
