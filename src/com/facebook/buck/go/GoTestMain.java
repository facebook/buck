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

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

public class GoTestMain extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey private final Tool testMainGen;
  @AddToRuleKey private final ImmutableSet<SourcePath> testSources;
  @AddToRuleKey private final GoTestCoverStep.Mode coverageMode;

  @AddToRuleKey(stringify = true)
  private final Path testPackage;

  @AddToRuleKey(stringify = true)
  private final ImmutableMap<Path, ImmutableMap<String, Path>> coverVariables;

  private final Path output;

  public GoTestMain(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool testMainGen,
      ImmutableSet<SourcePath> testSources,
      Path testPackage,
      ImmutableMap<Path, ImmutableMap<String, Path>> coverVariables,
      GoTestCoverStep.Mode coverageMode) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.testMainGen = testMainGen;
    this.testSources = testSources;
    this.testPackage = testPackage;
    this.output =
        BuildTargets.getScratchPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + "_test_main.go");
    this.coverVariables = coverVariables;
    this.coverageMode = coverageMode;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(
            new GoTestMainStep(
                getBuildTarget(),
                getProjectFilesystem().getRootPath(),
                testMainGen.getEnvironment(context.getSourcePathResolver()),
                testMainGen.getCommandPrefix(context.getSourcePathResolver()),
                coverageMode,
                coverVariables,
                testPackage,
                testSources
                    .stream()
                    .map(context.getSourcePathResolver()::getAbsolutePath)
                    .collect(ImmutableList.toImmutableList()),
                output))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
