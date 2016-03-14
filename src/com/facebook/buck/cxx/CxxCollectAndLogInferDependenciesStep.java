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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public final class CxxCollectAndLogInferDependenciesStep implements Step {

  private static final String SPLIT_TOKEN = "\t";

  private CxxInferAnalyze analysisRule;
  private ProjectFilesystem projectFilesystem;
  private Path outputFile;

  CxxCollectAndLogInferDependenciesStep(
      CxxInferAnalyze analysisRule,
      ProjectFilesystem projectFilesystem,
      Path outputFile) {
    this.analysisRule = analysisRule;
    this.projectFilesystem = projectFilesystem;
    this.outputFile = outputFile;
  }

  private String computeOutput(
      BuildTarget target,
      Path output) {
    return target.toString() +
        SPLIT_TOKEN +
        target.getFlavors().toString() +
        SPLIT_TOKEN +
        output.toString();
  }

  private String processCaptureRule(CxxInferCapture captureRule) {
    return computeOutput(captureRule.getBuildTarget(), captureRule.getPathToOutput());
  }

  private void processAnalysisRuleHelper(
      CxxInferAnalyze analysisRule,
      ImmutableList.Builder<String> accumulator) {
    accumulator.add(computeOutput(analysisRule.getBuildTarget(), analysisRule.getResultsDir()));
    accumulator.addAll(
        FluentIterable.from(analysisRule.getCaptureRules()).transform(
            new Function<CxxInferCapture, String>() {
              @Override
              public String apply(CxxInferCapture captureRule) {
                return processCaptureRule(captureRule);
              }
            }
        ));
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
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    ImmutableList<String> output;
    output = processAnalysisRule(analysisRule);
    projectFilesystem.writeLinesToPath(output, outputFile);
    return 0;
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
