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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.EventTypeMessage.EventType;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStepsRunner;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;

/** Common methods used by java and pipelining java command executors */
class StepExecutionUtils {

  private static final Logger LOG = Logger.get(StepExecutionUtils.class);

  private StepExecutionUtils() {}

  static void executeSteps(
      IsolatedEventBus eventBus,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock,
      String actionId,
      AbsPath ruleCellRoot,
      ImmutableList<IsolatedStep> steps)
      throws IOException {
    executeSteps(
        eventBus,
        eventsOutputStream,
        downwardProtocol,
        platform,
        processExecutor,
        console,
        clock,
        actionId,
        ruleCellRoot,
        steps,
        true);
  }

  static Optional<IsolatedExecutionContext> executeSteps(
      IsolatedEventBus eventBus,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock,
      String actionId,
      AbsPath ruleCellRoot,
      ImmutableList<IsolatedStep> steps,
      boolean closeExecutionContext)
      throws IOException {

    // create a new execution context
    IsolatedExecutionContext executionContext =
        IsolatedExecutionContext.of(
            eventBus,
            console,
            platform,
            processExecutor,
            ruleCellRoot,
            "javacd_action_id_" + actionId,
            clock);

    // use newly created execution context to execute steps
    try {
      executeSteps(executionContext, eventsOutputStream, downwardProtocol, actionId, steps);
    } finally {
      if (closeExecutionContext) {
        executionContext.close();
      }
    }

    // if need to close, then nothing to return,
    // if no need to close, then return newly created execution context for future reuse
    return closeExecutionContext ? Optional.empty() : Optional.of(executionContext);
  }

  static void executeSteps(
      IsolatedExecutionContext executionContext,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      String actionId,
      ImmutableList<IsolatedStep> steps)
      throws IOException {
    StepExecutionResult stepExecutionResult =
        IsolatedStepsRunner.executeWithDefaultExceptionHandling(steps, executionContext);
    ResultEvent resultEvent = getResultEvent(actionId, stepExecutionResult);
    writeResultEvent(downwardProtocol, eventsOutputStream, resultEvent);
  }

  private static ResultEvent getResultEvent(
      String actionId, StepExecutionResult stepExecutionResult) {
    int exitCode = stepExecutionResult.getExitCode();
    ResultEvent.Builder resultEventBuilder =
        ResultEvent.newBuilder().setActionId(actionId).setExitCode(exitCode);
    if (!stepExecutionResult.isSuccess()) {
      StringBuilder errorMessage = new StringBuilder();
      stepExecutionResult
          .getStderr()
          .ifPresent(
              stdErr ->
                  errorMessage.append("Std err: ").append(stdErr).append(System.lineSeparator()));
      stepExecutionResult
          .getCause()
          .ifPresent(
              cause -> {
                LOG.warn(cause, "%s failed with an exception.", actionId);
                String error;
                if (cause instanceof HumanReadableException) {
                  error = ((HumanReadableException) cause).getHumanReadableErrorMessage();
                } else {
                  error = Throwables.getStackTraceAsString(cause);
                }
                errorMessage.append("Cause: ").append(error);
              });
      if (errorMessage.length() > 0) {
        resultEventBuilder.setMessage(errorMessage.toString());
      }
    }

    return resultEventBuilder.build();
  }

  private static void writeResultEvent(
      DownwardProtocol downwardProtocol, OutputStream eventsOutputStream, ResultEvent resultEvent)
      throws IOException {
    writeEvent(EventType.RESULT_EVENT, resultEvent, eventsOutputStream, downwardProtocol);
  }

  static void writePipelineFinishedEvent(
      DownwardProtocol downwardProtocol, OutputStream eventsOutputStream) throws IOException {
    writeEvent(
        EventType.PIPELINE_FINISHED_EVENT,
        PipelineFinishedEvent.getDefaultInstance(),
        eventsOutputStream,
        downwardProtocol);
  }

  private static void writeEvent(
      EventType eventType,
      AbstractMessage event,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol)
      throws IOException {
    downwardProtocol.write(
        EventTypeMessage.newBuilder().setEventType(eventType).build(), event, eventsOutputStream);
  }
}
