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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

final class CxxCollectAndLogInferDependenciesStep implements Step {

  private Optional<CxxInferCaptureTransitive> captureTransitiveRule;
  private ProjectFilesystem projectFilesystem;
  private Path outputFile;

  private CxxCollectAndLogInferDependenciesStep(
      Optional<CxxInferCaptureTransitive> captureTransitiveRule,
      ProjectFilesystem projectFilesystem,
      Path outputFile) {
    this.captureTransitiveRule = captureTransitiveRule;
    this.projectFilesystem = projectFilesystem;
    this.outputFile = outputFile;
  }

  public static CxxCollectAndLogInferDependenciesStep fromCaptureTransitiveRule(
      CxxInferCaptureTransitive captureTransitiveRule,
      ProjectFilesystem projectFilesystem,
      Path outputFile) {
    return new CxxCollectAndLogInferDependenciesStep(
        Optional.of(captureTransitiveRule), projectFilesystem, outputFile);
  }

  private String processCaptureRule(CxxInferCapture captureRule) {
    return InferLogLine.fromBuildTarget(
            captureRule.getBuildTarget(), captureRule.getAbsolutePathToOutput())
        .toString();
  }

  private ImmutableList<String> processCaptureTransitiveRule(
      CxxInferCaptureTransitive captureTransitiveRule) {
    ImmutableList.Builder<String> outputBuilder = ImmutableList.builder();
    for (CxxInferCapture captureRule : captureTransitiveRule.getCaptureRules()) {
      outputBuilder.add(processCaptureRule(captureRule));
    }
    return outputBuilder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    ImmutableList<String> output;
    if (captureTransitiveRule.isPresent()) {
      output = processCaptureTransitiveRule(captureTransitiveRule.get());
    } else {
      throw new IllegalStateException("Expected non-empty analysis or capture rules in input");
    }
    projectFilesystem.writeLinesToPath(output, outputFile);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "infer-log-deps";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Log Infer's dependencies used for the analysis";
  }
}
