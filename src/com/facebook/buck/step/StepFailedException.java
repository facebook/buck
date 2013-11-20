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
import com.google.common.base.Optional;

@SuppressWarnings("serial")
public class StepFailedException extends Exception {

  private final Step step;
  private final int exitCode;

  private StepFailedException(String message, Step step, int exitCode) {
    super(message);
    this.step = step;
    this.exitCode = exitCode;
  }

  static StepFailedException createForFailingStep(Step step,
      ExecutionContext context,
      int exitCode,
      Optional<BuildTarget> buildTarget) {
    String message;
    if (buildTarget.isPresent()) {
      message = String.format("%s failed with exit code %d:\n%s",
          buildTarget.get().getFullyQualifiedName(),
          exitCode,
          step.getDescription(context));
    } else {
      message = String.format("Failed with exit code %d:\n%s",
          exitCode,
          step.getDescription(context));
    }
    return new StepFailedException(message, step, exitCode);
  }

  public Step getStep() {
    return step;
  }

  public int getExitCode() {
    return exitCode;
  }
}
