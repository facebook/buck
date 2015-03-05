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
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.Callable;

public interface StepRunner {

  /**
   * Note that this method blocks until the specified command terminates.
   */
  public void runStep(Step step) throws StepFailedException, InterruptedException;

  /**
   * Runs a BuildStep for a given BuildRule.
   *
   * Note that this method blocks until the specified command terminates.
   */
  public void runStepForBuildTarget(Step step, BuildTarget buildTarget)
      throws StepFailedException, InterruptedException;

  /**
   * In a new thread, executes of the list of commands and then invokes {@code interpretResults} to
   * return a value that represents the output of the commands.
   */
  public <T> ListenableFuture<T> runStepsAndYieldResult(
      List<Step> steps, Callable<T> interpretResults, BuildTarget buildTarget);

  public void runStepsInParallelAndWait(final List<Step> steps)
      throws StepFailedException, InterruptedException;

  /**
   * Execute callback in a new thread, once dependencies have completed.
   */
  public <T> ListenableFuture<Void> addCallback(
      ListenableFuture<List<T>> dependencies,
      FutureCallback<List<T>> callback);
}
