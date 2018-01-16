/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.ExitCode;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.ActionAndTargetGraphs;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RemoteBuildRuleCompletionNotifier;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** High level controls the distributed build. */
public class BuildController {
  private static final Logger LOG = Logger.get(BuildController.class);
  private static final int DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 500;

  private final PreBuildPhase preBuildPhase;
  private final BuildPhase buildPhase;
  private final PostBuildPhase postBuildPhase;

  private final AtomicReference<StampedeId> stampedeIdReference;

  /** Result of a distributed build execution. */
  public static class ExecutionResult {
    public final StampedeId stampedeId;
    public final int exitCode;

    public ExecutionResult(StampedeId stampedeId, int exitCode) {
      this.stampedeId = stampedeId;
      this.exitCode = exitCode;
    }
  }

  public BuildController(
      BuildExecutorArgs builderExecutorArgs,
      ImmutableSet<BuildTarget> topLevelTargets,
      ActionAndTargetGraphs buildGraphs,
      Optional<CachingBuildEngineDelegate> cachingBuildEngineDelegate,
      ListenableFuture<BuildJobState> asyncJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      ClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler,
      long maxTimeoutWaitingForLogsMillis,
      int statusPollIntervalMillis,
      boolean logMaterializationEnabled,
      RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier,
      AtomicReference<StampedeId> stampedeIdReference) {
    this.stampedeIdReference = stampedeIdReference;
    this.preBuildPhase =
        new PreBuildPhase(
            distBuildService,
            distBuildClientStats,
            asyncJobState,
            distBuildCellIndexer,
            buckVersion,
            builderExecutorArgs,
            topLevelTargets,
            buildGraphs);
    this.buildPhase =
        new BuildPhase(
            builderExecutorArgs,
            topLevelTargets,
            buildGraphs,
            cachingBuildEngineDelegate,
            distBuildService,
            distBuildClientStats,
            distBuildLogStateTracker,
            scheduler,
            statusPollIntervalMillis,
            remoteBuildRuleCompletionNotifier);
    this.postBuildPhase =
        new PostBuildPhase(
            distBuildService,
            distBuildClientStats,
            distBuildLogStateTracker,
            maxTimeoutWaitingForLogsMillis,
            logMaterializationEnabled);
  }

  public BuildController(
      BuildExecutorArgs buildExecutorArgs,
      ImmutableSet<BuildTarget> topLevelTargets,
      ActionAndTargetGraphs buildGraphs,
      Optional<CachingBuildEngineDelegate> cachingBuildEngineDelegate,
      ListenableFuture<BuildJobState> asyncJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      DistBuildService distBuildService,
      LogStateTracker distBuildLogStateTracker,
      BuckVersion buckVersion,
      ClientStatsTracker distBuildClientStats,
      ScheduledExecutorService scheduler,
      long maxTimeoutWaitingForLogsMillis,
      boolean logMaterializationEnabled,
      RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier,
      AtomicReference<StampedeId> stampedeIdReference) {
    this(
        buildExecutorArgs,
        topLevelTargets,
        buildGraphs,
        cachingBuildEngineDelegate,
        asyncJobState,
        distBuildCellIndexer,
        distBuildService,
        distBuildLogStateTracker,
        buckVersion,
        distBuildClientStats,
        scheduler,
        maxTimeoutWaitingForLogsMillis,
        DEFAULT_STATUS_POLL_INTERVAL_MILLIS,
        logMaterializationEnabled,
        remoteBuildRuleCompletionNotifier,
        stampedeIdReference);
  }

  /** Executes the tbuild and prints failures to the event bus. */
  public ExecutionResult executeAndPrintFailuresToEventBus(
      WeightedListeningExecutorService executorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus,
      InvocationInfo invocationInfo,
      BuildMode buildMode,
      int numberOfMinions,
      String repository,
      String tenantId,
      ListenableFuture<Optional<ParallelRuleKeyCalculator<RuleKey>>> ruleKeyCalculatorFuture)
      throws IOException, InterruptedException {
    Pair<StampedeId, ListenableFuture<Void>> stampedeIdAndPendingPrepFuture = null;
    try {
      stampedeIdAndPendingPrepFuture =
          preBuildPhase.runPreDistBuildLocalStepsAsync(
              executorService,
              projectFilesystem,
              fileHashCache,
              eventBus,
              invocationInfo.getBuildId(),
              buildMode,
              numberOfMinions,
              repository,
              tenantId,
              ruleKeyCalculatorFuture);
    } catch (IOException | RuntimeException ex) {
      LOG.error(ex, "Distributed build preparation steps failed.");
      return createFailedExecutionResult(
          Preconditions.checkNotNull(stampedeIdReference.get()), ExitCode.PREPARATION_STEP_FAILED);
    }

    stampedeIdAndPendingPrepFuture = Preconditions.checkNotNull(stampedeIdAndPendingPrepFuture);
    stampedeIdReference.set(stampedeIdAndPendingPrepFuture.getFirst());

    ListenableFuture<Void> pendingPrepFuture = stampedeIdAndPendingPrepFuture.getSecond();
    try {
      LOG.info("Waiting for pre-build preparation to finish.");
      pendingPrepFuture.get();
      LOG.info("Pre-build preparation finished.");
    } catch (InterruptedException ex) {
      pendingPrepFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw ex;
    } catch (ExecutionException ex) {
      LOG.error(ex, "Distributed build preparation async steps failed.");
      return createFailedExecutionResult(
          stampedeIdReference.get(), ExitCode.PREPARATION_ASYNC_STEP_FAILED);
    }

    BuildPhase.BuildResult buildResult = null;

    EventSender eventSender = new EventSender(eventBus);
    try {
      buildResult =
          buildPhase.runDistBuildAndUpdateConsoleStatus(
              executorService,
              eventSender,
              Preconditions.checkNotNull(stampedeIdReference.get()),
              buildMode,
              invocationInfo,
              ruleKeyCalculatorFuture);
    } catch (IOException | RuntimeException ex) { // Important: Don't swallow InterruptedException
      LOG.error(ex, "Distributed build step failed.");
      return createFailedExecutionResult(
          Preconditions.checkNotNull(stampedeIdReference.get()),
          ExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION);
    }

    // Note: always returns distributed exit code 0
    // TODO(alisdair,ruibm,shivanker): consider new exit code if failed to fetch finished stats
    return postBuildPhase.runPostDistBuildLocalSteps(
        executorService,
        buildResult.getBuildSlaveStatusList(),
        buildResult.getFinalBuildJob(),
        eventSender);
  }

  private static ExecutionResult createFailedExecutionResult(
      StampedeId stampedeId, ExitCode exitCode) {
    return new ExecutionResult(stampedeId, exitCode.getCode());
  }
}
