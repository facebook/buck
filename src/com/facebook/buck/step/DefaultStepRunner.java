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
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public final class DefaultStepRunner implements StepRunner {

  private static final Logger LOG = Logger.get(DefaultStepRunner.class);

  private final ExecutionContext context;

  public DefaultStepRunner(ExecutionContext executionContext) {
    this.context = executionContext;
  }

  @Override
  public void runStepForBuildTarget(Step step, Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException {

    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    String stepShortName = step.getShortName();
    String stepDescription = step.getDescription(context);
    UUID stepUuid = UUID.randomUUID();
    context.getBuckEventBus().logDebugAndPost(
        LOG, StepEvent.started(stepShortName, stepDescription, stepUuid));
    int exitCode = 1;
    try {
      exitCode = step.execute(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(step, e, buildTarget);
    } finally {
      context.getBuckEventBus().logDebugAndPost(
          LOG, StepEvent.finished(stepShortName, stepDescription, stepUuid, exitCode));
    }
    if (exitCode != 0) {
      throw StepFailedException.createForFailingStepWithExitCode(step,
          context,
          exitCode,
          buildTarget);
    }
  }

  @Override
  public <T> ListenableFuture<T> runStepsAndYieldResult(
      final List<Step> steps,
      final Callable<T> interpretResults,
      final Optional<BuildTarget> buildTarget,
      ListeningExecutorService listeningExecutorService) {
    Preconditions.checkState(!listeningExecutorService.isShutdown());
    Callable<T> callable = new Callable<T>() {

      @Override
      public T call() throws Exception {
        for (Step step : steps) {
          runStepForBuildTarget(step, buildTarget);
        }

        return interpretResults.call();
      }

    };

    return listeningExecutorService.submit(callable);
  }

  /**
   * Run multiple steps in parallel and block waiting for all of them to finish.  An
   * exception is thrown (immediately) if any step fails.
   *
   * @param steps List of steps to execute.
   */
  @Override
  public void runStepsInParallelAndWait(
      final List<Step> steps,
      final Optional<BuildTarget> target,
      ListeningExecutorService listeningExecutorService)
      throws StepFailedException, InterruptedException {
    List<Callable<Void>> callables = Lists.transform(steps,
        new Function<Step, Callable<Void>>() {
      @Override
      public Callable<Void> apply(final Step step) {
        return new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            runStepForBuildTarget(step, target);
            return null;
          }
        };
      }
    });

    try {
      MoreFutures.getAll(listeningExecutorService, callables);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, StepFailedException.class);

      // Programmer error.  Boo-urns.
      throw new RuntimeException(cause);
    }
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
