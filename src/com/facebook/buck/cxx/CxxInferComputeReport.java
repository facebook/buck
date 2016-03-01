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
import com.facebook.buck.json.JsonConcatenateStep;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Merge all the json reports together into one and emit a list of results dirs of each
 * capture and analysis target involved for the analysis itself.
 */
public class CxxInferComputeReport extends AbstractBuildRule {

  private CxxInferAnalyze analysisToReport;
  private ProjectFilesystem projectFilesystem;
  private Path outputDirectory;
  private Path depsOutput;
  private Path reportOutput;

  public CxxInferComputeReport(
      BuildRuleParams buildRuleParams,
      SourcePathResolver sourcePathResolver,
      CxxInferAnalyze analysisToReport) {
    super(buildRuleParams, sourcePathResolver);
    this.analysisToReport = analysisToReport;
    this.outputDirectory =
        BuildTargets.getGenPath(this.getBuildTarget(), "infer-%s");
    this.depsOutput = this.outputDirectory.resolve("infer-deps.txt");
    this.reportOutput = this.outputDirectory.resolve("report.json");
    this.projectFilesystem = getProjectFilesystem();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(reportOutput);
    buildableContext.recordArtifact(depsOutput);
    ImmutableSortedSet<Path> reportsToMergeFromDeps =
        FluentIterable.from(analysisToReport.getTransitiveAnalyzeRules())
            .transform(
                new Function<CxxInferAnalyze, Path>() {
                  @Override
                  public Path apply(CxxInferAnalyze input) {
                    return input.getPathToOutput();
                  }
                })
            .toSortedSet(Ordering.natural());

    ImmutableSortedSet<Path> reportsToMerge = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(reportsToMergeFromDeps)
        .add(analysisToReport.getPathToOutput())
        .build();

    return ImmutableList.<Step>builder()
        .add(new MkdirStep(projectFilesystem, outputDirectory))
        .add(
            new JsonConcatenateStep(
                projectFilesystem,
                reportsToMerge,
                reportOutput,
                "infer-merge-reports",
                "Merge Infer Reports"))
        .add(new CollectAndLogInferDependenciesStep())
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return reportOutput;
  }

  private final class CollectAndLogInferDependenciesStep implements Step {

    private static final String SPLIT_TOKEN = "\t";

    @Override
    public int execute(ExecutionContext context) throws IOException, InterruptedException {
      ImmutableList.Builder<String> pathsBuilder = ImmutableList.builder();
      pathsBuilder.add(
          analysisToReport.getBuildTarget() + SPLIT_TOKEN + analysisToReport.getResultsDir());
      for (CxxInferAnalyze analyzeRule : analysisToReport.getTransitiveAnalyzeRules()) {
        pathsBuilder.add(analyzeRule.getBuildTarget() + SPLIT_TOKEN + analyzeRule.getResultsDir());
        pathsBuilder.addAll(
            FluentIterable.from(analyzeRule.getCaptureRules()).transform(
                new Function<CxxInferCapture, String>() {
                  @Override
                  public String apply(CxxInferCapture captureRule) {
                    return
                        captureRule.getBuildTarget() + SPLIT_TOKEN + captureRule.getPathToOutput();
                  }
                }
            ).toList());
      }

      projectFilesystem.writeLinesToPath(pathsBuilder.build(), depsOutput);

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
}
