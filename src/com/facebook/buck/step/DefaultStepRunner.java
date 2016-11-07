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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

public final class DefaultStepRunner implements StepRunner {

  private static final Logger LOG = Logger.get(DefaultStepRunner.class);

  @Override
  public void runStepForBuildTarget(
      ExecutionContext context,
      Step step,
      Optional<BuildTarget> buildTarget) throws StepFailedException, InterruptedException {
    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    String stepShortName = step.getShortName();
    String stepDescription = step.getDescription(context);
    UUID stepUuid = UUID.randomUUID();
    StepEvent.Started started = StepEvent.started(stepShortName, stepDescription, stepUuid);
    context.getBuckEventBus().logDebugAndPost(
        LOG, started);
    StepExecutionResult executionResult = StepExecutionResult.ERROR;
    try {
      executionResult = step.execute(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(step, e, buildTarget);
    } finally {
      context.getBuckEventBus().logDebugAndPost(
          LOG, StepEvent.finished(started, executionResult.getExitCode()));
    }
    if (!executionResult.isSuccess()) {
      throw StepFailedException.createForFailingStepWithExitCode(step,
          context,
          executionResult,
          buildTarget);
    }
  }

  @Override
  public <T> ListenableFuture<T> runStepsAndYieldResult(
      ExecutionContext context,
      final List<Step> steps,
      final Callable<T> interpretResults,
      final Optional<BuildTarget> buildTarget,
      ListeningExecutorService listeningExecutorService,
      final StepRunningCallback callback) {
    Preconditions.checkState(!listeningExecutorService.isShutdown());
    Callable<T> callable = () -> {
      callback.stepsWillRun(buildTarget);
      for (Step step : steps) {
        runStepForBuildTarget(context, step, buildTarget);
      }
      callback.stepsDidRun(buildTarget);

      return interpretResults.call();
    };

    return listeningExecutorService.submit(callable);
  }

  @Override
  public <T> ListenableFuture<Void> addCallback(
      ListenableFuture<List<T>> dependencies,
      FutureCallback<List<T>> callback,
      ListeningExecutorService listeningExecutorService) {
    Preconditions.checkState(!listeningExecutorService.isShutdown());
    return MoreFutures.addListenableCallback(dependencies, callback, listeningExecutorService);
  }
}
