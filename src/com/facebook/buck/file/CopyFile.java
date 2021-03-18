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

package com.facebook.buck.file;

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
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.google.common.collect.ImmutableList;

/** Rule which copies files to the output path using the given layout. */
public class CopyFile extends ModernBuildRule<CopyFile.Impl> {

  public CopyFile(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      OutputPath outputPath,
      SourcePath sourcePath) {
    super(buildTarget, filesystem, finder, new Impl(outputPath, sourcePath));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  /** Rule implementation. */
  static class Impl implements Buildable {

    @AddToRuleKey(stringify = true)
    private final OutputPath outputPath;

    @AddToRuleKey private final SourcePath sourcePath;

    Impl(OutputPath outputPath, SourcePath sourcePath) {
      this.outputPath = outputPath;
      this.sourcePath = sourcePath;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList.Builder<Step> builder = ImmutableList.builder();

      RelPath output = outputPathResolver.resolvePath(outputPath);

      if (output.getParent() != null) {
        builder.add(MkdirIsolatedStep.of(output.getParent()));
      }

      builder.add(
          CopyIsolatedStep.forFile(
              buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath(),
              output.getPath()));

      return builder.build();
    }
  }
}
