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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Utility class for running {@link Step}s */
public final class StepRunner {

  private static final Logger LOG = Logger.get(StepRunner.class);

  private StepRunner() {}

  /**
   * Runs a single {@link Step}
   *
   * @param context the {@link ExecutionContext} containing information and console logging
   *     utilities for the {@link Step}
   * @param step the {@link Step} to execute
   * @throws StepFailedException if the step failed
   * @throws InterruptedException if an interrupt occurred while executing the {@link Step}
   */
  public static void runStep(ExecutionContext context, Step step, Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException {
    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    String stepShortName = step.getShortName();
    String stepDescription = step.getDescription(context);
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started started = StepEvent.started(stepShortName, stepDescription, stepUuid);
    String buildTargetName = buildTarget.map(BuildTarget::getFullyQualifiedName).orElse("N/A");
    logStepEvent(context, started, buildTargetName);
    context.getBuckEventBus().post(started);
    StepExecutionResult executionResult = StepExecutionResults.ERROR;
    try {
      executionResult = step.execute(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(step, context, e);
    } finally {
      StepEvent.Finished finished = StepEvent.finished(started, executionResult.getExitCode());
      logStepEvent(context, finished, buildTargetName, executionResult.getExecutedCommand());
      context.getBuckEventBus().post(finished);
    }
    if (!executionResult.isSuccess()) {
      throw StepFailedException.createForFailingStepWithExitCode(step, context, executionResult);
    }
  }

  private static void logStepEvent(
      ExecutionContext context, StepEvent stepEvent, String buildTargetName) {
    logStepEvent(context, stepEvent, buildTargetName, ImmutableList.of());
  }

  private static void logStepEvent(
      ExecutionContext context,
      StepEvent stepEvent,
      String buildTargetName,
      ImmutableList<String> command) {
    if (command.isEmpty()) {
      LOG.verbose("%s for build rule <%s>", stepEvent, buildTargetName);
    } else {
      LOG.verbose(
          "%s for build rule <%s>, executed command: %s", stepEvent, buildTargetName, command);
      if (context.getVerbosity().shouldPrintCommand()) {
        context.getStdErr().println(command);
      }
    }
  }
}
