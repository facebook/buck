/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UnstrippedNativeLibraries extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasRuntimeDeps, SupportsInputBasedRuleKey {
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> inputs;

  UnstrippedNativeLibraries(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> inputs) {
    super(
        buildTarget,
        projectFilesystem,
        buildRuleParams
            .withoutExtraDeps()
            .withDeclaredDeps(ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(inputs))));
    this.inputs = inputs;
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getOutputPath());
    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    getOutputPath().getParent())))
        .add(
            new AbstractExecutionStep("write_native_libs_paths") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) throws IOException {
                List<String> lines =
                    inputs
                        .stream()
                        .map(
                            sp ->
                                getProjectFilesystem()
                                    .relativize(
                                        buildContext.getSourcePathResolver().getAbsolutePath(sp)))
                        .map(Object::toString)
                        .collect(Collectors.toList());
                getProjectFilesystem().writeLinesToPath(lines, getOutputPath());
                return StepExecutionResults.SUCCESS;
              }
            })
        .build();
  }

  private Path getOutputPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/unstripped-native-libs.txt");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputPath());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return inputs
        .stream()
        .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
        .map(BuildRule::getBuildTarget);
  }
}
