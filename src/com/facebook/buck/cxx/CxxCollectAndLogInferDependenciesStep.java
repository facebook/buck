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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;

/** Isolated step that writes captured infer rules into an output path in infer log line format. */
class CxxCollectAndLogInferDependenciesStep extends IsolatedStep {

  private final ImmutableSet<Pair<BuildTarget, AbsPath>> captureRules;
  private final RelPath outputPath;

  public CxxCollectAndLogInferDependenciesStep(
      ImmutableSet<Pair<BuildTarget, AbsPath>> captureRules, RelPath outputPath) {
    this.captureRules = captureRules;
    this.outputPath = outputPath;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {

    AbsPath ruleCellRoot = context.getRuleCellRoot();

    RelPath parentDirectory = outputPath.getParent();
    if (parentDirectory != null) {
      ProjectFilesystemUtils.mkdirs(ruleCellRoot, parentDirectory.getPath());
    }

    ImmutableList<String> lines =
        captureRules.stream()
            .map(this::toInferLogLine)
            .map(InferLogLine::getFormattedString)
            .collect(ImmutableList.toImmutableList());

    ProjectFilesystemUtils.writeLinesToPath(ruleCellRoot, lines, outputPath.getPath());

    return StepExecutionResults.SUCCESS;
  }

  private InferLogLine toInferLogLine(Pair<BuildTarget, AbsPath> captureRule) {
    BuildTarget buildTarget = captureRule.getFirst();
    AbsPath output = captureRule.getSecond();
    return InferLogLine.of(buildTarget, output);
  }

  @Override
  public String getShortName() {
    return "infer-log-deps";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return "Log Infer's dependencies used for the analysis";
  }
}
