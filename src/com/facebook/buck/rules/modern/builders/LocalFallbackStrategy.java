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
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Strategy that makes sure failed remote builds fallback to be executed locally. */
public class LocalFallbackStrategy implements BuildRuleStrategy {
  private static final Logger LOG = Logger.get(LocalFallbackStrategy.class);

  private final BuildRuleStrategy mainBuildRuleStrategy;

  public LocalFallbackStrategy(BuildRuleStrategy mainBuildRuleStrategy) {
    this.mainBuildRuleStrategy = mainBuildRuleStrategy;
  }

  @Override
  public void close() throws IOException {
    mainBuildRuleStrategy.close();
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    return new FallbackStrategyBuildResult(
        rule.toString(), mainBuildRuleStrategy.build(rule, strategyContext), strategyContext);
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    return mainBuildRuleStrategy.canBuild(instance);
  }

  /**
   * Contains the combined result of running the remote execution and local execution if necessary.
   */
  static class FallbackStrategyBuildResult implements StrategyBuildResult {
    private final String buildRuleName;
    private final StrategyBuildResult remoteStrategyBuildResult;
    private final SettableFuture<Optional<BuildResult>> combinedFinalResult;
    private final BuildStrategyContext strategyContext;
    private final Object lock;

    private Optional<ListenableFuture<Optional<BuildResult>>> localStrategyBuildResult;
    private boolean hasCancellationBeenRequested;

    public FallbackStrategyBuildResult(
        String buildRuleName,
        StrategyBuildResult remoteStrategyBuildResult,
        BuildStrategyContext strategyContext) {
      this.lock = new Object();
      this.localStrategyBuildResult = Optional.empty();
      this.buildRuleName = buildRuleName;
      this.remoteStrategyBuildResult = remoteStrategyBuildResult;
      this.strategyContext = strategyContext;
      this.combinedFinalResult = SettableFuture.create();
      this.hasCancellationBeenRequested = false;

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
        if (!isLocalBuildAlreadyRunning()) {
          remoteStrategyBuildResult.cancel(cause);
        } else {
          localStrategyBuildResult.get().cancel(true);
        }
      }
    }

    @Override
    public boolean cancelIfNotStarted(Throwable reason) {
      synchronized (lock) {
        if (!isLocalBuildAlreadyRunning() && remoteStrategyBuildResult.cancelIfNotStarted(reason)) {
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
        try {
          Optional<BuildResult> result = mainBuildResult.get();
          Preconditions.checkState(result.isPresent());
          if (result.get().isSuccess()) {
            // Remote build worked flawlessly first time. :)
            combinedFinalResult.set(result);
          } else {
            handleRemoteBuildFailedWithActionError(result);
          }
        } catch (InterruptedException e) {
          if (hasCancellationBeenRequested) {
            combinedFinalResult.setException(e);
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
      fallbackBuildToLocalStrategy();
    }

    private void handleRemoteBuildFailedWithException(Throwable t) {
      LOG.warn(
          t, "Remote build failed for a build rule so trying locally now for [%s].", buildRuleName);
      fallbackBuildToLocalStrategy();
    }

    private void fallbackBuildToLocalStrategy() {
      ListenableFuture<Optional<BuildResult>> future =
          Futures.submitAsync(
              strategyContext::runWithDefaultBehavior, strategyContext.getExecutorService());
      localStrategyBuildResult = Optional.of(future);
      future.addListener(() -> onLocalBuildFinished(future), strategyContext.getExecutorService());
    }

    private void onLocalBuildFinished(ListenableFuture<Optional<BuildResult>> future) {
      synchronized (lock) {
        try {
          // Remote build failed but local build finished.
          combinedFinalResult.set(future.get());
        } catch (InterruptedException e) {
          if (hasCancellationBeenRequested) {
            combinedFinalResult.setException(e);
            return;
          }

          handleLocalBuildFailedWithException(e);
        } catch (ExecutionException e) {
          handleLocalBuildFailedWithException(e.getCause());
        }
      }
    }

    private void handleLocalBuildFailedWithException(Throwable t) {
      LOG.error(t, "Local fallback for one build rule failed as well for [%s].", buildRuleName);
      combinedFinalResult.setException(t);
    }

    private boolean isLocalBuildAlreadyRunning() {
      return localStrategyBuildResult.isPresent();
    }
  }
}
