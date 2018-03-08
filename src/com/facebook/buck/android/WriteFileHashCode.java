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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

/** Computes the hash of a file and writes it as the output. */
public class WriteFileHashCode extends ModernBuildRule<WriteFileHashCode> implements Buildable {
  @AddToRuleKey private final SourcePath inputPath;
  @AddToRuleKey private final OutputPath outputPath;

  public WriteFileHashCode(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToFile) {
    super(buildTarget, projectFilesystem, ruleFinder, WriteFileHashCode.class);
    this.inputPath = pathToFile;
    this.outputPath = new OutputPath("file.hash");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    return ImmutableList.of(
        new AbstractExecutionStep("writing_file_hash") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            filesystem.writeContentsToPath(
                filesystem
                    .computeSha1(buildContext.getSourcePathResolver().getAbsolutePath(inputPath))
                    .getHash(),
                outputPathResolver.resolvePath(outputPath));
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(outputPath);
  }
}
