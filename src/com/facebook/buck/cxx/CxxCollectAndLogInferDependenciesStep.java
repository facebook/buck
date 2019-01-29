/*
 * Copyright 2016-present Facebook, Inc.
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

  private Optional<CxxInferAnalyze> analysisRule;
  private Optional<CxxInferCaptureTransitive> captureOnlyRule;
  private ProjectFilesystem projectFilesystem;
  private Path outputFile;

  private CxxCollectAndLogInferDependenciesStep(
      Optional<CxxInferAnalyze> analysisRule,
      Optional<CxxInferCaptureTransitive> captureOnlyRule,
      ProjectFilesystem projectFilesystem,
      Path outputFile) {
    this.analysisRule = analysisRule;
    this.captureOnlyRule = captureOnlyRule;
    this.projectFilesystem = projectFilesystem;
    this.outputFile = outputFile;
  }

  public static CxxCollectAndLogInferDependenciesStep fromAnalyzeRule(
      CxxInferAnalyze analyzeRule, ProjectFilesystem projectFilesystem, Path outputFile) {
    return new CxxCollectAndLogInferDependenciesStep(
        Optional.of(analyzeRule), Optional.empty(), projectFilesystem, outputFile);
  }

  public static CxxCollectAndLogInferDependenciesStep fromCaptureOnlyRule(
      CxxInferCaptureTransitive captureOnlyRule,
      ProjectFilesystem projectFilesystem,
      Path outputFile) {
    return new CxxCollectAndLogInferDependenciesStep(
        Optional.empty(), Optional.of(captureOnlyRule), projectFilesystem, outputFile);
  }

  private String processCaptureRule(CxxInferCapture captureRule) {
    return InferLogLine.fromBuildTarget(
            captureRule.getBuildTarget(), captureRule.getAbsolutePathToOutput())
        .toString();
  }

  private ImmutableList<String> processCaptureOnlyRule(CxxInferCaptureTransitive captureOnlyRule) {
    ImmutableList.Builder<String> outputBuilder = ImmutableList.builder();
    for (CxxInferCapture captureRule : captureOnlyRule.getCaptureRules()) {
      outputBuilder.add(processCaptureRule(captureRule));
    }
    return outputBuilder.build();
  }

  private void processAnalysisRuleHelper(
      CxxInferAnalyze analysisRule, ImmutableList.Builder<String> accumulator) {
    accumulator.add(
        InferLogLine.fromBuildTarget(
                analysisRule.getBuildTarget(), analysisRule.getAbsolutePathToResultsDir())
            .toString());
    accumulator.addAll(
        analysisRule.getCaptureRules().stream().map(this::processCaptureRule).iterator());
  }

  private ImmutableList<String> processAnalysisRule(CxxInferAnalyze analyzeRule) {
    ImmutableList.Builder<String> outputBuilder = ImmutableList.builder();
    processAnalysisRuleHelper(analyzeRule, outputBuilder);
    for (CxxInferAnalyze analyzeDepRule : analyzeRule.getTransitiveAnalyzeRules()) {
      processAnalysisRuleHelper(analyzeDepRule, outputBuilder);
    }
    return outputBuilder.build();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    ImmutableList<String> output;
    if (analysisRule.isPresent()) {
      output = processAnalysisRule(analysisRule.get());
    } else if (captureOnlyRule.isPresent()) {
      output = processCaptureOnlyRule(captureOnlyRule.get());
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
