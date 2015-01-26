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

import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.concurrent.MoreExecutors;
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

  private static final Logger LOG = Logger.get(DefaultStepRunner.class);

  // Shutdown timeout should be longer than the maximum runtime of a single step as some
  // steps ignore interruption. The longest ever recorded step execution time as of
  // 2014-07-08 was ~6 minutes, so a timeout of 10 minutes should be sufficient.
  private static final long SHUTDOWN_TIMEOUT_MINUTES = 10;
  private final ExecutionContext context;
  private final ListeningExecutorService listeningExecutorService;

  public DefaultStepRunner(ExecutionContext context,
                           int numThreads) {
    this(context,
        listeningDecorator(
            newMultiThreadExecutor(
                new CommandThreadFactory("DefaultStepRunner"),
                numThreads)));
  }

  @VisibleForTesting
  public DefaultStepRunner(
      ExecutionContext executionContext,
      ListeningExecutorService listeningExecutorService) {
    this.context = executionContext;
    this.listeningExecutorService = listeningExecutorService;

  }

  @Override
  public void runStep(Step step) throws StepFailedException, InterruptedException {
    runStepInternal(step, Optional.<BuildTarget>absent());
  }

  @Override
  public void runStepForBuildTarget(Step step, BuildTarget buildTarget)
      throws StepFailedException, InterruptedException {
    runStepInternal(step, Optional.of(buildTarget));
  }

  protected void runStepInternal(final Step step, final Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException {

    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(step.getDescription(context));
    }

    context.getBuckEventBus().logDebugAndPost(
        LOG, StepEvent.started(step, step.getDescription(context)));
    int exitCode = 1;
    try {
      exitCode = step.execute(context);
    } catch (IOException | RuntimeException e) {
      throw StepFailedException.createForFailingStepWithException(step, e, buildTarget);
    } finally {
      context.getBuckEventBus().logDebugAndPost(
          LOG, StepEvent.finished(step, step.getDescription(context), exitCode));
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
  public void runStepsInParallelAndWait(final List<Step> steps)
      throws StepFailedException, InterruptedException {
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
      MoreFutures.getAll(listeningExecutorService, callables);
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
    Preconditions.checkState(!listeningExecutorService.isShutdown());
    Futures.addCallback(dependencies, callback, listeningExecutorService);
  }

  @Override
  public void close() throws IOException {
    close(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
  }

  @VisibleForTesting
  void close(long timeout, TimeUnit unit) {
    MoreExecutors.shutdownOrThrow(
        listeningExecutorService,
        timeout,
        unit,
        new InterruptionFailedException("Failed to shutdown ExecutorService."));
  }
}
