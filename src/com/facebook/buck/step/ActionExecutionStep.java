/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionContext;
import java.io.IOException;
import java.nio.file.Path;

/**
 * This is an adaptor between the {@link Action} interfaces and the {@link Step} interfaces, which
 * allows the {@link com.facebook.buck.core.build.engine.impl.CachingBuildEngine} to execute {@link
 * Action}s
 */
public class ActionExecutionStep implements Step {
  private final Action action;
  private final boolean shouldDeleteTemporaries;
  private final Path buildFileRootPath;

  public ActionExecutionStep(
      Action action, boolean shouldDeleteTemporaries, Path buildFileRootPath) {
    this.action = action;
    this.shouldDeleteTemporaries = shouldDeleteTemporaries;
    this.buildFileRootPath = buildFileRootPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ActionExecutionContext executionContext =
        ImmutableActionExecutionContext.of(
            context.getBuckEventBus(), shouldDeleteTemporaries, buildFileRootPath);
    ActionExecutionResult result = action.execute(executionContext);
    if (result instanceof ActionExecutionResult.ActionExecutionSuccess) {
      return StepExecutionResult.of(0, result.getStdErr());
    } else if (result instanceof ActionExecutionResult.ActionExecutionFailure) {
      return StepExecutionResult.of(-1, result.getStdErr());
    } else {
      throw new IllegalStateException("Unknown action execution result " + result);
    }
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
