/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent.Result;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Strategy that makes sure failed remote builds fallback to be executed locally. */
public class LocalFallbackStrategy implements BuildRuleStrategy {
  private static final Logger LOG = Logger.get(LocalFallbackStrategy.class);

  private final BuildRuleStrategy mainBuildRuleStrategy;
  private final BuckEventBus eventBus;

  public LocalFallbackStrategy(BuildRuleStrategy mainBuildRuleStrategy, BuckEventBus eventBus) {
    this.mainBuildRuleStrategy = mainBuildRuleStrategy;
    this.eventBus = eventBus;
  }

  @Override
  public void close() throws IOException {
    mainBuildRuleStrategy.close();
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    return new FallbackStrategyBuildResult(
        rule.getFullyQualifiedName(),
        mainBuildRuleStrategy.build(rule, strategyContext),
        strategyContext,
        eventBus);
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    return mainBuildRuleStrategy.canBuild(instance);
  }

  /** Thrown when execution needs to be halted because of cancellation */
  public static class RemoteActionCancelledException extends Exception {
    RemoteActionCancelledException(String message) {
      super(message);
    }
  }

  /**
   * Contains the combined result of running the remote execution and local execution if necessary.
   */
  static class FallbackStrategyBuildResult implements StrategyBuildResult {
    private final String buildTarget;
    private final StrategyBuildResult remoteStrategyBuildResult;
    private final SettableFuture<Optional<BuildResult>> combinedFinalResult;
    private final BuildStrategyContext strategyContext;
    private final Object lock;
    private final BuckEventBus eventBus;
    private final LocalFallbackEvent.Started startedEvent;
    private final Stopwatch remoteExecutionTimer;

    private Optional<ListenableFuture<Optional<BuildResult>>> localStrategyBuildResult;
    private boolean hasCancellationBeenRequested;
    private Optional<LocalFallbackEvent.Result> remoteBuildResult;
    private Optional<String> remoteBuildErrorMessage;

    public FallbackStrategyBuildResult(
        String buildTarget,
        StrategyBuildResult remoteStrategyBuildResult,
        BuildStrategyContext strategyContext,
        BuckEventBus eventBus) {
      this.lock = new Object();
      this.localStrategyBuildResult = Optional.empty();
      this.buildTarget = buildTarget;
      this.remoteStrategyBuildResult = remoteStrategyBuildResult;
      this.strategyContext = strategyContext;
      this.combinedFinalResult = SettableFuture.create();
      this.hasCancellationBeenRequested = false;
      this.eventBus = eventBus;
      this.startedEvent = LocalFallbackEvent.createStarted(buildTarget);
      this.remoteBuildResult = Optional.empty();
      this.remoteExecutionTimer = Stopwatch.createStarted();
      this.remoteBuildErrorMessage = Optional.empty();

      this.eventBus.post(this.startedEvent);
      this.remoteStrategyBuildResult
          .getBuildResult()
          .addListener(
              () -> onMainBuildFinished(remoteStrategyBuildResult.getBuildResult()),
              MoreExecutors.directExecutor());
    }

    @Override
    public void cancel(Throwable cause) {
      synchronized (lock) {
        hasCancellationBeenRequested = true;
        if (isLocalBuildAlreadyRunning()) {
          // Don't interrupt ongoing local builds to avoid leaving buck-out in a bad state.
          localStrategyBuildResult.get().cancel(/* mayInterruptIfRunning */ false);
        } else {
          remoteStrategyBuildResult.cancel(cause);
        }

        combinedFinalResult.cancel(false);
      }
    }

    @Override
    public boolean cancelIfNotComplete(Throwable reason) {
      synchronized (lock) {
        if (!isLocalBuildAlreadyRunning()
            && remoteStrategyBuildResult.cancelIfNotComplete(reason)) {
          hasCancellationBeenRequested = true;
          return true;
        }
      }

      return false;
    }

    @Override
    public ListenableFuture<Optional<BuildResult>> getBuildResult() {
      return combinedFinalResult;
    }

    private void onMainBuildFinished(ListenableFuture<Optional<BuildResult>> mainBuildResult) {
      synchronized (lock) {
        remoteExecutionTimer.stop();
        try {
          Optional<BuildResult> result = mainBuildResult.get();
          Preconditions.checkState(result.isPresent());
          if (result.get().isSuccess()) {
            // Remote build worked flawlessly first time. :)
            completeCombinedFuture(result, Result.SUCCESS, Result.NOT_RUN);
          } else {
            handleRemoteBuildFailedWithActionError(result);
          }
        } catch (InterruptedException e) {
          if (hasCancellationBeenRequested) {
            completeCombinedFutureWithException(e, Result.INTERRUPTED, Result.NOT_RUN);
            return;
          }

          handleRemoteBuildFailedWithException(e);
        } catch (ExecutionException e) {
          handleRemoteBuildFailedWithException(e.getCause());
        }
      }
    }

    private void handleRemoteBuildFailedWithActionError(Optional<BuildResult> result) {
      LOG.warn(
          "Remote build failed so trying locally. The error was: [%s]", result.get().toString());
      remoteBuildResult = Optional.of(Result.FAIL);
      remoteBuildErrorMessage = Optional.of(result.toString());
      fallbackBuildToLocalStrategy();
    }

    private void handleRemoteBuildFailedWithException(Throwable t) {
      LOG.warn(
          t, "Remote build failed for a build rule so trying locally now for [%s].", buildTarget);
      remoteBuildResult =
          Optional.of(t instanceof InterruptedException ? Result.INTERRUPTED : Result.EXCEPTION);
      remoteBuildErrorMessage = Optional.of(t.toString());
      fallbackBuildToLocalStrategy();
    }

    private void fallbackBuildToLocalStrategy() {
      if (hasCancellationBeenRequested) {
        completeCombinedFutureWithException(
            new RemoteActionCancelledException(
                "Unable to fall back to Local Strategy, execution has been cancelled"),
            remoteBuildResult.get(),
            Result.NOT_RUN);
        return;
      }
      ListenableFuture<Optional<BuildResult>> future =
          Futures.submitAsync(
              strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService());
      localStrategyBuildResult = Optional.of(future);
      future.addListener(() -> onLocalBuildFinished(future), MoreExecutors.directExecutor());
    }

    private void onLocalBuildFinished(ListenableFuture<Optional<BuildResult>> future) {
      synchronized (lock) {
        try {
          // Remote build failed but local build finished.
          Optional<BuildResult> result = future.get();
          result =
              Optional.of(
                  BuildResult.builder()
                      .from(result.get())
                      .setStrategyResult(
                          "local fallback - "
                              + (result.get().getSuccessOptional().isPresent()
                                  ? "success"
                                  : "fail"))
                      .build());
          completeCombinedFuture(
              result,
              remoteBuildResult.get(),
              result.get().isSuccess() ? Result.SUCCESS : Result.FAIL);
        } catch (InterruptedException e) {
          if (hasCancellationBeenRequested) {
            completeCombinedFutureWithException(e, remoteBuildResult.get(), Result.INTERRUPTED);
            return;
          }

          handleLocalBuildFailedWithException(e);
        } catch (ExecutionException e) {
          handleLocalBuildFailedWithException(e.getCause());
        }
      }
    }

    private void handleLocalBuildFailedWithException(Throwable t) {
      LOG.error(t, "Local fallback for one build rule failed as well for [%s].", buildTarget);
      completeCombinedFutureWithException(t, remoteBuildResult.get(), Result.EXCEPTION);
    }

    private boolean isLocalBuildAlreadyRunning() {
      return localStrategyBuildResult.isPresent();
    }

    private void completeCombinedFuture(Optional<BuildResult> result, Result remote, Result local) {
      combinedFinalResult.set(result);
      eventBus.post(
          startedEvent.createFinished(
              remote,
              local,
              remoteExecutionTimer.elapsed(TimeUnit.MILLISECONDS),
              remoteBuildErrorMessage));
    }

    private void completeCombinedFutureWithException(
        Throwable throwable, Result remote, Result local) {
      combinedFinalResult.setException(throwable);
      eventBus.post(
          startedEvent.createFinished(
              remote,
              local,
              remoteExecutionTimer.elapsed(TimeUnit.MILLISECONDS),
              remoteBuildErrorMessage));
    }
  }
}
