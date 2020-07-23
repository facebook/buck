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

package com.facebook.buck.step.isolatedsteps;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.StepFailedException;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

/**
 * Step runner that executes the steps the given {@link IsolatedStep}s.
 *
 * <p>See also {@link com.facebook.buck.step.StepRunner}.
 */
class IsolatedStepsRunner {

  private IsolatedStepsRunner() {}

  static StepExecutionResult execute(
      ImmutableList<IsolatedStep> steps, IsolatedExecutionContext executionContext) {
    try {
      for (IsolatedStep step : steps) {
        runStep(executionContext, step);
        rethrowIgnoredInterruptedException(step);
      }
      return StepExecutionResults.SUCCESS;
    } catch (StepFailedException e) {
      executionContext.logError(e, "Failed to execute steps");
    } catch (InterruptedException e) {
      executionContext.logError(e, "Received interrupt");
    }
    return StepExecutionResults.ERROR;
  }

  @SuppressWarnings(
      "PMD.EmptyFinallyBlock") // TODO(irenewchen): Remove this once step events are logged
  private static void runStep(IsolatedExecutionContext context, IsolatedStep step)
      throws InterruptedException, StepFailedException {
    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getIsolatedStepDescription(context));
    }

    // TODO(irenewchen): Log step event started and post to event bus

    StepExecutionResult executionResult = StepExecutionResults.ERROR;
    try {
      executionResult = step.executeIsolatedStep(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(
          step, step.getIsolatedStepDescription(context), e);
    } finally {
      // TODO(irenewchen): Log step event finished event
    }
    if (!executionResult.isSuccess()) {
      throw StepFailedException.createForFailingStepWithExitCode(
          step, step.getIsolatedStepDescription(context), true, executionResult);
    }
  }

  private static void rethrowIgnoredInterruptedException(IsolatedStep step)
      throws InterruptedException {
    // Check for interruptions that may have been ignored by step.
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
      throw new InterruptedException(
          "Thread was interrupted inside the executed step: " + step.getShortName());
    }
  }
}
