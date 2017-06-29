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
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;
import java.util.function.Supplier;

public interface StepRunner {

  default ListenableFuture<Void> runStepsForBuildTarget(
      ExecutionContext executionContext,
      Supplier<ImmutableList<? extends Step>> stepsSupplier,
      Optional<BuildTarget> optionalTarget,
      ListeningExecutorService service) {
    return service.submit(
        () -> {
          // Get and run all of the commands.
          ImmutableList<? extends Step> steps = stepsSupplier.get();

          for (Step step : steps) {
            runStepForBuildTarget(executionContext, step, optionalTarget);

            // Check for interruptions that may have been ignored by step.
            if (Thread.interrupted()) {
              Thread.currentThread().interrupt();
              throw new InterruptedException();
            }
          }
          return null;
        });
  }

  /**
   * Runs a BuildStep for a given BuildRule.
   *
   * <p>Note that this method blocks until the specified command terminates.
   */
  void runStepForBuildTarget(ExecutionContext context, Step step, Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException;
}
