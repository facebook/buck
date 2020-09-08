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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nonnull;

/** Computes content hash for every item in the given directory and writes those hashes to disk. */
public class AppleComputeDirectoryContentHashes
    extends ModernBuildRule<AppleComputeDirectoryContentHashes.Impl> {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-directory-content-hashes");

  public AppleComputeDirectoryContentHashes(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath directorySourcePath) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new AppleComputeDirectoryContentHashes.Impl(directorySourcePath));
  }

  @Nonnull
  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {
    @AddToRuleKey private final SourcePath directorySourcePath;
    @AddToRuleKey private final OutputPath output;

    Impl(SourcePath directorySourcePath) {
      this.directorySourcePath = directorySourcePath;
      output = new OutputPath("hashes.json");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

      ImmutableMap.Builder<RelPath, String> builder = ImmutableMap.builder();

      AbsPath directoryPath =
          buildContext.getSourcePathResolver().getAbsolutePath(directorySourcePath);
      stepsBuilder.add(
          new AppleComputeDirectoryFirstLevelContentHashesStep(directoryPath, filesystem, builder));

      stepsBuilder.add(
          new AppleWriteHashPerFileStep(
              "persist-directory-content-hashes",
              builder::build,
              outputPathResolver.resolvePath(output).getPath(),
              filesystem));

      return stepsBuilder.build();
    }
  }
}
