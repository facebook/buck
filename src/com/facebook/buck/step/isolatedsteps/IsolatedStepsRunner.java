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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.StepEvent;
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
public class IsolatedStepsRunner {

  private static final Logger LOG = Logger.get(IsolatedStepsRunner.class);

  private IsolatedStepsRunner() {}

  /**
   * Executes the given {@link IsolatedStep} instances with the given {@link
   * IsolatedExecutionContext} and throws {@link StepFailedException} if it occurred during the
   * execution of any steps.
   */
  public static StepExecutionResult execute(
      ImmutableList<IsolatedStep> steps, IsolatedExecutionContext executionContext)
      throws StepFailedException {
    try {
      for (IsolatedStep step : steps) {
        runStep(executionContext, step);
        rethrowIgnoredInterruptedException(step);
      }
      return StepExecutionResults.SUCCESS;
    } catch (InterruptedException e) {
      executionContext.logError(e, "Received interrupt");
    }
    return StepExecutionResults.ERROR;
  }

  /**
   * Executes the given {@link IsolatedStep} instances with the given {@link
   * IsolatedExecutionContext} and handles {@link StepFailedException} (log it with an error level).
   *
   * <p>The difference from this method and {@link #execute} is that this method logs and do not
   * propagate {@link StepFailedException} in case it occurred.
   */
  public static StepExecutionResult executeWithDefaultExceptionHandling(
      ImmutableList<IsolatedStep> steps, IsolatedExecutionContext executionContext) {
    try {
      return execute(steps, executionContext);
    } catch (StepFailedException e) {
      LOG.warn(e, "Failed to execute isolated steps");
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setCause((Exception) e.getCause())
          .build();
    }
  }

  private static void runStep(IsolatedExecutionContext context, IsolatedStep step)
      throws InterruptedException, StepFailedException {
    String stepDescription = step.getIsolatedStepDescription(context);
    if (context.getVerbosity().shouldPrintCommand()) {
      context.postEvent(ConsoleEvent.info(stepDescription));
    }

    StepEvent.Started started = StepEvent.started(step.getShortName(), stepDescription);
    IsolatedEventBus isolatedEventBus = context.getIsolatedEventBus();
    String actionId = context.getActionId();
    isolatedEventBus.post(started, actionId);
    StepExecutionResult executionResult = StepExecutionResults.ERROR;
    try {
      executionResult = step.executeIsolatedStep(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(
          step, descriptionForStep(step, context), e);
    } finally {
      isolatedEventBus.post(StepEvent.finished(started, executionResult.getExitCode()), actionId);
    }
    if (!executionResult.isSuccess()) {
      throw StepFailedException.createForFailingIsolatedStepWithExitCode(
          step, stepDescription, executionResult);
    }
  }

  private static String descriptionForStep(IsolatedStep step, IsolatedExecutionContext context) {
    return context.getVerbosity().shouldPrintCommand()
        ? step.getIsolatedStepDescription(context)
        : step.getShortName();
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
