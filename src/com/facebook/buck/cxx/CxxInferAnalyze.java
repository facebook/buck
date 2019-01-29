/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymCopyStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;

class CxxInferAnalyze extends AbstractBuildRule {

  private final Path resultsDir;
  private final Path reportFile;
  private final Path specsDir;
  private final Path specsPathList;

  @AddToRuleKey private final InferBuckConfig inferConfig;
  private final ImmutableSet<CxxInferCapture> captureRules;
  private final ImmutableSet<CxxInferAnalyze> transitiveAnalyzeRules;
  private final ImmutableSortedSet<BuildRule> buildDeps;

  CxxInferAnalyze(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      InferBuckConfig inferConfig,
      ImmutableSet<CxxInferCapture> captureRules,
      ImmutableSet<CxxInferAnalyze> transitiveAnalyzeRules) {
    super(buildTarget, projectFilesystem);
    this.resultsDir =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), this.getBuildTarget(), "infer-analysis-%s");
    this.reportFile = this.resultsDir.resolve("report.json");
    this.specsDir = this.resultsDir.resolve("specs");
    this.specsPathList = this.resultsDir.resolve("specs_path_list.txt");
    this.inferConfig = inferConfig;
    this.captureRules = captureRules;
    this.transitiveAnalyzeRules = transitiveAnalyzeRules;
    this.buildDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(captureRules)
            .addAll(transitiveAnalyzeRules)
            .build();
  }

  private ImmutableSortedSet<SourcePath> getSpecsOfAllDeps() {
    return transitiveAnalyzeRules
        .stream()
        .map(rule -> ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), rule.getSpecsDir()))
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  public Path getSpecsDir() {
    return specsDir;
  }

  public Path getAbsolutePathToResultsDir() {
    return getProjectFilesystem().resolve(resultsDir);
  }

  public ImmutableSet<CxxInferCapture> getCaptureRules() {
    return captureRules;
  }

  public ImmutableSet<CxxInferAnalyze> getTransitiveAnalyzeRules() {
    return transitiveAnalyzeRules;
  }

  private ImmutableList<String> getAnalyzeCommand() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder
        .add(inferConfig.getInferTopLevel().toString())
        .add("analyze")
        .add("--results-dir", resultsDir.toString())
        .add("--project-root", getProjectFilesystem().getRootPath().toString())
        .add("--specs-dir-list-file", getProjectFilesystem().resolve(specsPathList).toString());

    return commandBuilder.build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(specsDir);
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), specsDir)))
        .add(
            new SymCopyStep(
                getProjectFilesystem(),
                captureRules
                    .stream()
                    .map(CxxInferCapture::getSourcePathToOutput)
                    .map(context.getSourcePathResolver()::getRelativePath)
                    .collect(ImmutableList.toImmutableList()),
                resultsDir))
        .add(
            new AbstractExecutionStep("write_specs_path_list") {
              @Override
              public StepExecutionResult execute(ExecutionContext executionContext)
                  throws IOException {
                ImmutableList<String> specsDirsWithAbsolutePath =
                    getSpecsOfAllDeps()
                        .stream()
                        .map(
                            input ->
                                context.getSourcePathResolver().getAbsolutePath(input).toString())
                        .collect(ImmutableList.toImmutableList());
                getProjectFilesystem().writeLinesToPath(specsDirsWithAbsolutePath, specsPathList);
                return StepExecutionResults.SUCCESS;
              }
            })
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(), getAnalyzeCommand(), ImmutableMap.of()))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), reportFile);
  }
}
