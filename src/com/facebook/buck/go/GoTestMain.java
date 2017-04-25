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

package com.facebook.buck.go;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

public class GoTestMain extends AbstractBuildRule {
  @AddToRuleKey private final Tool testMainGen;
  @AddToRuleKey private final ImmutableSet<SourcePath> testSources;

  @AddToRuleKey(stringify = true)
  private final Path testPackage;

  private final Path output;

  public GoTestMain(
      BuildRuleParams buildRuleParams,
      Tool testMainGen,
      ImmutableSet<SourcePath> testSources,
      Path testPackage) {
    super(buildRuleParams);
    this.testMainGen = testMainGen;
    this.testSources = testSources;
    this.testPackage = testPackage;
    this.output =
        BuildTargets.getScratchPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + "_test_main.go");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        MkdirStep.of(getProjectFilesystem(), output.getParent()),
        new GoTestMainStep(
            getProjectFilesystem().getRootPath(),
            testMainGen.getEnvironment(context.getSourcePathResolver()),
            testMainGen.getCommandPrefix(context.getSourcePathResolver()),
            /* coverageMode */ "",
            /* coverageVariables */ ImmutableMap.of(),
            testPackage,
            testSources
                .stream()
                .map(context.getSourcePathResolver()::getAbsolutePath)
                .collect(MoreCollectors.toImmutableList()),
            output));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }
}
