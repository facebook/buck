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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.json.JsonConcatenateStep;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.SortedSet;

/**
 * Merge all the json reports together into one and emit a list of results dirs of each capture and
 * analysis target involved for the analysis itself.
 */
class CxxInferComputeReport extends AbstractBuildRule implements HasPostBuildSteps {

  private CxxInferAnalyze analysisToReport;
  private ProjectFilesystem projectFilesystem;
  private Path outputDirectory;
  private Path reportOutput;

  public CxxInferComputeReport(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      CxxInferAnalyze analysisToReport) {
    super(buildTarget, projectFilesystem);
    this.analysisToReport = analysisToReport;
    this.outputDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), this.getBuildTarget(), "infer-%s");
    this.reportOutput = this.outputDirectory.resolve("report.json");
    this.projectFilesystem = getProjectFilesystem();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(analysisToReport.getTransitiveAnalyzeRules())
        .add(analysisToReport)
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(reportOutput);
    ImmutableSortedSet<Path> reportsToMergeFromDeps =
        analysisToReport
            .getTransitiveAnalyzeRules()
            .stream()
            .map(CxxInferAnalyze::getSourcePathToOutput)
            .map(context.getSourcePathResolver()::getAbsolutePath)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    ImmutableSortedSet<Path> reportsToMerge =
        ImmutableSortedSet.<Path>naturalOrder()
            .addAll(reportsToMergeFromDeps)
            .add(
                context
                    .getSourcePathResolver()
                    .getAbsolutePath(analysisToReport.getSourcePathToOutput()))
            .build();

    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), outputDirectory)))
        .add(
            new JsonConcatenateStep(
                projectFilesystem,
                reportsToMerge,
                reportOutput,
                "infer-merge-reports",
                "Merge Infer Reports"))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), reportOutput);
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), outputDirectory)))
        .add(
            CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(
                analysisToReport,
                getProjectFilesystem(),
                this.outputDirectory.resolve("infer-deps.txt")))
        .build();
  }
}
