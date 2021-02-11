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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.HasBrokenInputBasedRuleKey;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.android.MergeAssetsStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

/**
 * MergeAssets adds the assets for an APK into the output of aapt.
 *
 * <p>Android's ApkBuilder seemingly would do this, but it doesn't actually compress the assets that
 * are added.
 */
public class MergeAssets extends ModernBuildRule<MergeAssets.Impl> {

  public MergeAssets(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> baseApk,
      ImmutableSortedSet<SourcePath> assetsDirectories) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(assetsDirectories, baseApk));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  static class Impl implements Buildable, HasBrokenInputBasedRuleKey {
    // TODO(cjhopman): This should be an input-based rule, but the asset directories are from
    // symlink trees and the file hash caches don't currently handle those correctly. The symlink
    // trees shouldn't actually be necessary anymore as we can just take the full list of source
    // paths directly here.
    @AddToRuleKey private final ImmutableSet<SourcePath> assetsDirectories;
    @AddToRuleKey private final Optional<SourcePath> baseApk;
    @AddToRuleKey private final OutputPath outputPath;

    Impl(ImmutableSet<SourcePath> assetsDirectories, Optional<SourcePath> baseApk) {
      this.assetsDirectories = assetsDirectories;
      this.baseApk = baseApk;
      this.outputPath = new OutputPath("merged.assets.ap_");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter pathResolver = buildContext.getSourcePathResolver();

      return ImmutableList.of(
          new MergeAssetsStep(
              outputPathResolver.resolvePath(outputPath),
              baseApk.map(path -> pathResolver.getRelativePath(filesystem, path)),
              assetsDirectories.stream()
                  .map(dir -> pathResolver.getRelativePath(filesystem, dir))
                  .collect(ImmutableSet.toImmutableSet())));
    }
  }
}
