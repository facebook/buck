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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.android.MergeJarResourcesStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

/** Merges resources from third party jars for exo-for-resources. */
public class MergeThirdPartyJarResources extends ModernBuildRule<MergeThirdPartyJarResources.Impl> {

  protected MergeThirdPartyJarResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableCollection<SourcePath> pathsToThirdPartyJars) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            ImmutableSortedSet.copyOf(pathsToThirdPartyJars), new OutputPath("java.resources")));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().mergedPath);
  }

  static class Impl implements Buildable {
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> pathsToThirdPartyJars;
    @AddToRuleKey private final OutputPath mergedPath;

    Impl(ImmutableSortedSet<SourcePath> pathsToThirdPartyJars, OutputPath mergedPath) {
      this.pathsToThirdPartyJars = pathsToThirdPartyJars;
      this.mergedPath = mergedPath;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableSortedSet<RelPath> thirdPartyJars =
          buildContext
              .getSourcePathResolver()
              .getAllRelativePaths(filesystem, pathsToThirdPartyJars);
      buildContext.getSourcePathResolver().getAllAbsolutePaths(pathsToThirdPartyJars);
      return ImmutableList.of(
          new MergeJarResourcesStep(
              thirdPartyJars,
              filesystem.resolve(outputPathResolver.resolvePath(mergedPath).getPath())));
    }
  }
}
