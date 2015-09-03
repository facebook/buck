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

import com.facebook.buck.cxx.CxxInferEnhancer.CxxInferCaptureAndAnalyzeRules;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymCopyStep;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

public class CxxInferAnalyze extends AbstractBuildRule {

  private CxxInferCaptureAndAnalyzeRules captureAndAnalyzeRules;

  private final Path resultsDir;
  private final Path reportFile;
  private final Path specsDir;

  @AddToRuleKey
  private final CxxInferTools inferTools;

  CxxInferAnalyze(
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      CxxInferTools inferTools,
      CxxInferCaptureAndAnalyzeRules captureAndAnalyzeRules) {
    super(buildRuleParams, pathResolver);
    this.captureAndAnalyzeRules = captureAndAnalyzeRules;
    this.resultsDir = BuildTargets.getGenPath(
        this.getBuildTarget(),
        "infer-analysis-%s");
    this.reportFile = this.resultsDir.resolve("report.json");
    this.specsDir = this.resultsDir.resolve("specs");
    this.inferTools = inferTools;
  }

  private ImmutableSortedSet<Path> getSpecsOfAllDeps() {
    return FluentIterable.from(captureAndAnalyzeRules.allAnalyzeRules)
        .transform(
            new Function<CxxInferAnalyze, Path>() {
              @Override
              public Path apply(CxxInferAnalyze input) {
                return input.getSpecsDir();
              }
            }
        )
        .toSortedSet(Ordering.natural());
  }

  public Path getSpecsDir() {
    return specsDir;
  }

  public ImmutableSet<CxxInferAnalyze> getTransitiveAnalyzeRules() {
    return captureAndAnalyzeRules.allAnalyzeRules;
  }

  private ImmutableList<String> getAnalyzeCommand(ImmutableSortedSet<Path> specsDirs) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder
        .addAll(inferTools.topLevel.getCommandPrefix(getResolver()))
        .add("--project_root", getProjectFilesystem().getRootPath().toString())
        .add("--out", resultsDir.toString());

    for (Path specDir : specsDirs) {
      commandBuilder.add("--specs-dir", getProjectFilesystem().resolve(specDir).toString());
    }

    commandBuilder.add("--", "analyze");

    return commandBuilder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(specsDir);
    buildableContext.recordArtifact(this.getPathToOutput());
    return ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), specsDir))
        .add(
            new SymCopyStep(
                getProjectFilesystem(),
                FluentIterable.from(captureAndAnalyzeRules.captureRules)
                    .transform(
                        new Function<CxxInferCapture, Path>() {
                          @Override
                          public Path apply(CxxInferCapture input) {
                            return input.getPathToOutput();
                          }
                        }).toList(),
                resultsDir))
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(),
                getAnalyzeCommand(getSpecsOfAllDeps())))
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return this.reportFile;
  }

}
