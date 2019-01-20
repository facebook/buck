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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.pipeline.RulePipelineState;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** A ModernBuildRule that @SupportsPipelining. */
public abstract class PipelinedModernBuildRule<
        State extends RulePipelineState, T extends PipelinedBuildable<State>>
    extends ModernBuildRule<T> implements SupportsPipelining<State> {
  protected PipelinedModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      T buildable) {
    super(buildTarget, filesystem, ruleFinder, buildable);
  }

  @Override
  public final ImmutableList<? extends Step> getPipelinedBuildSteps(
      BuildContext context, BuildableContext buildableContext, State state) {
    ImmutableList.Builder<Path> outputsBuilder = ImmutableList.builder();
    recordOutputs(outputsBuilder::add);
    ImmutableList<Path> outputs = outputsBuilder.build();
    outputs.forEach(buildableContext::recordArtifact);
    OutputPathResolver outputPathResolver = getOutputPathResolver();
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    ModernBuildRule.getSetupStepsForBuildable(
        context, getProjectFilesystem(), outputs, stepsBuilder, outputPathResolver);
    stepsBuilder.addAll(
        getBuildable()
            .getPipelinedBuildSteps(
                context,
                getProjectFilesystem(),
                state,
                outputPathResolver,
                getBuildCellPathFactory(context, getProjectFilesystem(), outputPathResolver)));
    return stepsBuilder.build();
  }
}
