/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class WriteStringTemplateRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final SourcePath template;

  @AddToRuleKey private final ImmutableMap<String, String> values;

  @AddToRuleKey private final boolean executable;

  public WriteStringTemplateRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Path output,
      SourcePath template,
      ImmutableMap<String, String> values,
      boolean executable) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.output = output;
    this.template = template;
    this.values = values;
    this.executable = executable;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    steps.add(
        new StringTemplateStep(
            context.getSourcePathResolver().getAbsolutePath(template),
            getProjectFilesystem(),
            output,
            values));
    if (executable) {
      steps.add(
          new AbstractExecutionStep("chmod +x") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              MostFiles.makeExecutable(getProjectFilesystem().resolve(output));
              return StepExecutionResult.of(0, Optional.empty());
            }
          });
    }
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public static WriteStringTemplateRule from(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Path output,
      SourcePath template,
      ImmutableMap<String, String> values,
      boolean executable) {
    return new WriteStringTemplateRule(
        target,
        projectFilesystem,
        baseParams
            .withDeclaredDeps(ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(template)))
            .withoutExtraDeps(),
        output,
        template,
        values,
        executable);
  }
}
