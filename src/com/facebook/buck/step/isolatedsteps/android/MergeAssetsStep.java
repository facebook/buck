/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.resources.MergeAssetsUtils;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

/** Step to construct build outputs for exo-for-resources. */
public class MergeAssetsStep extends IsolatedStep {
  private final RelPath relativePathToMergedAssets;
  private final Optional<RelPath> relativePathToBaseApk;
  private final ImmutableSet<RelPath> assetsDirectories;

  public MergeAssetsStep(
      RelPath relativePathToMergedAssets,
      Optional<RelPath> relativePathToBaseApk,
      ImmutableSet<RelPath> assetsDirectories) {
    this.relativePathToMergedAssets = relativePathToMergedAssets;
    this.relativePathToBaseApk = relativePathToBaseApk;
    this.assetsDirectories = assetsDirectories;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    MergeAssetsUtils.mergeAssets(
        ProjectFilesystemUtils.getPathForRelativePath(
            context.getRuleCellRoot(), relativePathToMergedAssets),
        relativePathToBaseApk.map(
            baseApk ->
                ProjectFilesystemUtils.getPathForRelativePath(context.getRuleCellRoot(), baseApk)),
        context.getRuleCellRoot(),
        assetsDirectories);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "merge_assets";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format(
        "merge_assets %s %s",
        relativePathToMergedAssets,
        relativePathToBaseApk.map(RelPath::toString).orElse("no_base_apk"));
  }
}
