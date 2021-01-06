/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.step.impl;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;

/**
 * This is an adaptor between the {@link Action} interfaces and the {@link Step} interfaces, which
 * allows the {@link com.facebook.buck.core.build.engine.impl.CachingBuildEngine} to execute {@link
 * Action}s
 */
public class ActionExecutionStep implements Step {
  private final Action action;
  private final ArtifactFilesystem artifactFilesystem;

  public ActionExecutionStep(Action action, ArtifactFilesystem artifactFilesystem) {
    this.action = action;
    this.artifactFilesystem = artifactFilesystem;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    ActionExecutionContext executionContext =
        ActionExecutionContext.of(
            context.getBuckEventBus(),
            artifactFilesystem,
            context.getProcessExecutor(),
            context.getEnvironment(),
            context.getBuildCellRootPath());

    /*
     * Create the output directory for the step's rule, and then delete any output artifacts that
     * already exist. We do this, rather than deleting the whole directory, because if a rule
     * has multiple outputs, we don't want to delete outputs whose actions may not be run
     */
    // TODO(pjameson): If an output is removed from an action, find a way to delete these orphaned
    //                 artifacts
    executionContext.getArtifactFilesystem().createPackagePaths(action.getOutputs());
    executionContext.getArtifactFilesystem().removeBuildArtifacts(action.getOutputs());

    ActionExecutionResult result = action.execute(executionContext);
    StepExecutionResult.Builder stepExecutionResultBuilder =
        StepExecutionResult.builder()
            .setExecutedCommand(result.getCommand())
            .setStderr(result.getStdErr());
    if (result instanceof ActionExecutionResult.ActionExecutionSuccess) {
      return stepExecutionResultBuilder.setExitCode(StepExecutionResults.SUCCESS_EXIT_CODE).build();
    }
    if (result instanceof ActionExecutionResult.ActionExecutionFailure) {
      return stepExecutionResultBuilder
          .setExitCode(-1)
          .setCause(((ActionExecutionResult.ActionExecutionFailure) result).getException())
          .build();
    }
    throw new IllegalStateException("Unknown action execution result " + result);
  }

  @Override
  public String getShortName() {
    return "action-step_" + action.getShortName();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "running action: " + action;
  }
}
