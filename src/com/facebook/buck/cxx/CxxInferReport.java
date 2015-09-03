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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/*
 * This merges together all the reports coming from all the dependencies
 */
public class CxxInferReport extends AbstractBuildRule {

  private ImmutableSortedSet<Path> reportsToMerge;
  private Path resultsDir;
  private Path report;

  public CxxInferReport(
      BuildRuleParams buildRuleParams,
      SourcePathResolver sourcePathResolver,
      ImmutableSortedSet<Path> reportsToMerge) {
    super(buildRuleParams, sourcePathResolver);
    this.reportsToMerge = reportsToMerge;
    this.resultsDir = BuildTargets.getGenPath(this.getBuildTarget(), "infer-%s");
    this.report = this.resultsDir.resolve("report.json");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(report);
    return ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), resultsDir))
        .add(new InferMergeReportsStep(getProjectFilesystem(), reportsToMerge, report))
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return report;
  }
}
