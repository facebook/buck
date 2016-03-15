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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CxxInferCaptureTransitive extends AbstractBuildRule implements HasRuntimeDeps {

  private ImmutableSet<CxxInferCapture> captureRules;
  private Path outputDirectory;
  private Path depsOutput;

  public CxxInferCaptureTransitive(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableSet<CxxInferCapture> captureRules) {
    super(params, pathResolver);
    this.captureRules = captureRules;
    this.outputDirectory = BuildTargets.getGenPath(this.getBuildTarget(), "infer-%s");
    this.depsOutput = this.outputDirectory.resolve("infer-deps.txt");
  }

  public ImmutableSet<CxxInferCapture> getCaptureRules() {
    return captureRules;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(captureRules)
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(depsOutput);
    return ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), outputDirectory))
        .add(
            CxxCollectAndLogInferDependenciesStep.fromCaptureOnlyRule(
                this,
                getProjectFilesystem(),
                depsOutput)
        )
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return depsOutput;
  }
}
