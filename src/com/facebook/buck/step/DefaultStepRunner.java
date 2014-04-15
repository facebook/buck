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

import static com.facebook.buck.util.concurrent.MoreExecutors.newMultiThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class DefaultStepRunner implements StepRunner, Closeable {

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;
  private final ExecutionContext context;
  private final ListeningExecutorService listeningExecutorService;

  public DefaultStepRunner(ExecutionContext context,
                           int numThreads) {
    this(context, listeningDecorator(newMultiThreadExecutor("DefaultStepRunner", numThreads)));
  }

  @VisibleForTesting
  public DefaultStepRunner(
      ExecutionContext executionContext,
      ListeningExecutorService listeningExecutorService) {
    this.context = Preconditions.checkNotNull(executionContext);
    this.listeningExecutorService = Preconditions.checkNotNull(listeningExecutorService);

  }

  @Override
  public void runStep(Step step) throws StepFailedException {
    runStepInternal(step, Optional.<BuildTarget>absent());
  }

  @Override
  public void runStepForBuildTarget(Step step, BuildTarget buildTarget) throws StepFailedException {
    runStepInternal(step, Optional.of(buildTarget));
  }

  protected void runStepInternal(final Step step, final Optional<BuildTarget> buildTarget)
      throws StepFailedException {
    Preconditions.checkNotNull(step);

    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    context.postEvent(StepEvent.started(step, step.getDescription(context)));
    int exitCode = 1;
    try {
      exitCode = step.execute(context);
    } catch (Throwable t) {
      throw StepFailedException.createForFailingStepWithException(step, t, buildTarget);
    } finally {
      context.postEvent(StepEvent.finished(step, step.getDescription(context), exitCode));
    }
    if (exitCode != 0) {
      throw StepFailedException.createForFailingStepWithExitCode(step,
          context,
          exitCode,
          buildTarget);
    }
  }

  @Override
  public <T> ListenableFuture<T> runStepsAndYieldResult(final List<Step> steps,
                                                        final Callable<T> interpretResults,
                                                        final BuildTarget buildTarget) {
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
  public void runStepsInParallelAndWait(final List<Step> steps) throws StepFailedException {
    List<Callable<Void>> callables = Lists.transform(steps,
        new Function<Step, Callable<Void>>() {
      @Override
      public Callable<Void> apply(final Step step) {
        return new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            runStep(step);
            return null;
          }
        };
      }
    });

    try {
      MoreFutures.getAllUninterruptibly(listeningExecutorService, callables);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, StepFailedException.class);

      // Programmer error.  Boo-urns.
      throw new RuntimeException(cause);
    }
  }

  @Override
  public <T> void addCallback(
      ListenableFuture<List<T>> dependencies,
      FutureCallback<List<T>> callback) {
    Futures.addCallback(dependencies, callback, listeningExecutorService);
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() throws IOException {
    listeningExecutorService.shutdown();
    try {
      // Allow tasks to complete.
      listeningExecutorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // Ignore InterruptedException since we're in the process of being shutdown.
    }
  }
}
