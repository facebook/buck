/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public final class DefaultStepRunner implements StepRunner {

  private static final Logger LOG = Logger.get(DefaultStepRunner.class);

  @Override
  public void runStepForBuildTarget(
      ExecutionContext context, Step step, Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException {
    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    String stepShortName = step.getShortName();
    String stepDescription = step.getDescription(context);
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started started = StepEvent.started(stepShortName, stepDescription, stepUuid);
    LOG.verbose(started.toString());
    context.getBuckEventBus().post(started);
    StepExecutionResult executionResult = StepExecutionResults.ERROR;
    try {
      executionResult = step.execute(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(step, context, e, buildTarget);
    } finally {
      StepEvent.Finished finished = StepEvent.finished(started, executionResult.getExitCode());
      LOG.verbose(finished.toString());
      context.getBuckEventBus().post(finished);
    }
    if (!executionResult.isSuccess()) {
      throw StepFailedException.createForFailingStepWithExitCode(
          step, context, executionResult, buildTarget);
    }
  }
}
