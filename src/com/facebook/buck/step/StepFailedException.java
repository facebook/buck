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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import java.util.Optional;

@SuppressWarnings("serial")
public class StepFailedException extends Exception {

  private final Step step;
  private final int exitCode;

  /** Callers should use {@link #createForFailingStepWithExitCode} unless in a unit test. */
  @VisibleForTesting
  public StepFailedException(String message, Step step, int exitCode) {
    super(message);
    this.step = step;
    this.exitCode = exitCode;
  }

  static StepFailedException createForFailingStepWithExitCode(
      Step step,
      ExecutionContext context,
      StepExecutionResult executionResult,
      Optional<BuildTarget> buildTarget) {
    int exitCode = executionResult.getExitCode();
    String nameOrDescription =
        context.getVerbosity().shouldPrintCommand()
            ? step.getDescription(context)
            : step.getShortName();
    String message;
    if (buildTarget.isPresent()) {
      message =
          String.format(
              "%s failed with exit code %d:\n%s",
              buildTarget.get().getFullyQualifiedName(), exitCode, nameOrDescription);
    } else {
      message = String.format("Failed with exit code %d:\n%s", exitCode, nameOrDescription);
    }
    Optional<String> stderr = executionResult.getStderr();
    if (stderr.isPresent()) {
      message += "\nstderr: " + stderr.get();
    }
    return new StepFailedException(message, step, exitCode);
  }

  static StepFailedException createForFailingStepWithException(
      Step step, ExecutionContext context, Throwable throwable, Optional<BuildTarget> buildTarget) {
    if (throwable instanceof HumanReadableException) {
      return createForFailingStepWithHumanReadableException(
          step, context, (HumanReadableException) throwable, buildTarget);
    }

    CapturingPrintStream printStream = new CapturingPrintStream();
    throwable.printStackTrace(printStream);
    String stackTrace = printStream.getContentsAsString(Charsets.UTF_8);

    String message;
    if (buildTarget.isPresent()) {
      message =
          String.format(
              "%s failed on step %s with an exception:\n%s\n%s",
              buildTarget.get().getFullyQualifiedName(),
              step.getShortName(),
              throwable.getMessage(),
              stackTrace);
    } else {
      message =
          String.format(
              "Failed on step %s with an exception:\n%s\n%s",
              step.getShortName(), throwable.getMessage(), stackTrace);
    }
    return new StepFailedException(message, step, 1);
  }

  private static StepFailedException createForFailingStepWithHumanReadableException(
      Step step,
      ExecutionContext context,
      HumanReadableException exception,
      Optional<BuildTarget> buildTarget) {
    String description = step.getDescription(context);
    String message;
    if (buildTarget.isPresent()) {
      message =
          String.format(
              "%s failed:\n%s\n%s",
              buildTarget.get().getFullyQualifiedName(),
              description,
              exception.getHumanReadableErrorMessage());
    } else {
      message =
          String.format("Failed:\n%s\n%s", description, exception.getHumanReadableErrorMessage());
    }
    return new StepFailedException(message, step, 1);
  }

  public Step getStep() {
    return step;
  }

  public int getExitCode() {
    return exitCode;
  }
}
