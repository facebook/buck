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

package com.facebook.buck.distributed.build_slave;

import static com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker.SlaveEvents.REVERSE_DEPENDENCY_QUEUE_CREATION_TIME;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildArtifactCacheImpl;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Factory for multi-slave implementations of DistBuildModeRunners. */
public class MultiSlaveBuildModeRunnerFactory {
  private static final Logger LOG = Logger.get(MultiSlaveBuildModeRunnerFactory.class);
  private static final String LOCALHOST_ADDRESS = "localhost";

  /**
   * Create a {@link CoordinatorModeRunner}.
   *
   * @param isLocalMinionAlsoRunning - are we going to have a local minion working on this build?
   * @return a new instance of the {@link CoordinatorModeRunner}.
   */
  public static CoordinatorModeRunner createCoordinator(
      ListenableFuture<DelegateAndGraphs> delegateAndGraphsFuture,
      List<BuildTarget> topLevelTargetsToBuild,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      Optional<BuildId> clientBuildId,
      boolean isLocalMinionAlsoRunning,
      Path logDirectoryPath,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      BuckEventBus eventBus,
      ListeningExecutorService executorService,
      ArtifactCache remoteCache,
      ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> asyncRuleKeyCalculator,
      HealthCheckStatsTracker healthCheckStatsTracker,
      Optional<BuildSlaveTimingStatsTracker> timingStatsTracker) {

    ListenableFuture<BuildTargetsQueue> queueFuture =
        Futures.transformAsync(
            asyncRuleKeyCalculator,
            ruleKeyCalculator ->
                Futures.transform(
                    delegateAndGraphsFuture,
                    graphs -> {
                      timingStatsTracker.ifPresent(
                          tracker -> tracker.startTimer(REVERSE_DEPENDENCY_QUEUE_CREATION_TIME));
                      BuildTargetsQueue queue;
                      try (ArtifactCacheByBuildRule artifactCache =
                          new DistBuildArtifactCacheImpl(
                              graphs.getActionGraphAndResolver().getResolver(),
                              executorService,
                              remoteCache,
                              eventBus,
                              ruleKeyCalculator,
                              Optional.empty())) {
                        queue =
                            new CacheOptimizedBuildTargetsQueueFactory(
                                    graphs.getActionGraphAndResolver().getResolver(),
                                    artifactCache,
                                    distBuildConfig.isDeepRemoteBuildEnabled(),
                                    ruleKeyCalculator.getRuleDepsCache())
                                .createBuildTargetsQueue(
                                    topLevelTargetsToBuild,
                                    coordinatorBuildRuleEventsPublisher,
                                    distBuildConfig.getMostBuildRulesFinishedPercentageThreshold());
                      } catch (Exception e) {
                        LOG.error(e, "Failed to create BuildTargetsQueue.");
                        throw new RuntimeException(e);
                      }
                      timingStatsTracker.ifPresent(
                          tracker -> tracker.stopTimer(REVERSE_DEPENDENCY_QUEUE_CREATION_TIME));
                      return queue;
                    },
                    executorService),
            executorService);
    Optional<String> minionQueue = distBuildConfig.getMinionQueue();
    Preconditions.checkArgument(
        minionQueue.isPresent(),
        "Minion queue name is missing to be able to run in Coordinator mode.");
    CoordinatorEventListener listenerAndMinionCountProvider =
        new CoordinatorEventListener(
            distBuildService, stampedeId, minionQueue.get(), isLocalMinionAlsoRunning);
    MinionHealthTracker minionHealthTracker =
        new MinionHealthTracker(
            new DefaultClock(),
            distBuildConfig.getMaxMinionSilenceMillis(),
            distBuildConfig.getHearbeatServiceRateMillis(),
            distBuildConfig.getSlowHeartbeatWarningThresholdMillis(),
            healthCheckStatsTracker);

    ChromeTraceBuckConfig chromeTraceBuckConfig =
        distBuildConfig.getBuckConfig().getView(ChromeTraceBuckConfig.class);

    Optional<URI> traceUploadUri = chromeTraceBuckConfig.getTraceUploadUriIfEnabled();

    return new CoordinatorModeRunner(
        queueFuture,
        stampedeId,
        listenerAndMinionCountProvider,
        logDirectoryPath,
        coordinatorBuildRuleEventsPublisher,
        distBuildService,
        clientBuildId,
        traceUploadUri,
        minionHealthTracker,
        listenerAndMinionCountProvider);
  }

  /**
   * Create a {@link MinionModeRunner}.
   *
   * @param localBuildExecutor - instance of {@link BuildExecutor} object to be used by the minion.
   * @return a new instance of the {@link MinionModeRunner}.
   */
  public static MinionModeRunner createMinion(
      ListenableFuture<BuildExecutor> localBuildExecutor,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      BuildSlaveRunId buildSlaveRunId,
      String coordinatorAddress,
      OptionalInt coordinatorPort,
      DistBuildConfig distBuildConfig,
      MinionBuildProgressTracker minionBuildProgressTracker,
      double availableBuildCapacityRatio,
      BuckEventBus eventBus) {
    Preconditions.checkArgument(
        availableBuildCapacityRatio > 0, availableBuildCapacityRatio + " is not > 0");
    Preconditions.checkArgument(
        availableBuildCapacityRatio <= 1, availableBuildCapacityRatio + " is not <= 1");

    MinionModeRunner.BuildCompletionChecker checker =
        () -> {
          BuildJob job = distBuildService.getCurrentBuildJobState(stampedeId);
          return BuildStatusUtil.isTerminalBuildStatus(job.getStatus());
        };

    int availableBuildCapacity =
        distBuildConfig
            .getBuckConfig()
            .getView(ResourcesConfig.class)
            .getConcurrencyLimit()
            .threadLimit;

    // Adjust by ratio. E.g. if ratio is 0.5 and we have 8 cores, minion will use 4 cores.
    Double availableCapacityDouble =
        Double.valueOf(availableBuildCapacityRatio * availableBuildCapacity);
    availableBuildCapacity = availableCapacityDouble.intValue();

    // Ensure value wasn't rounded down to 0. We always need more than 1 core to make progress.
    availableBuildCapacity = Math.max(1, availableBuildCapacity);

    return new MinionModeRunner(
        coordinatorAddress,
        coordinatorPort,
        localBuildExecutor,
        stampedeId,
        buildSlaveRunId,
        distBuildConfig
            .getBuckConfig()
            .getView(ResourcesConfig.class)
            .getConcurrencyLimit()
            .threadLimit,
        checker,
        distBuildConfig.getMinionPollLoopIntervalMillis(),
        minionBuildProgressTracker,
        distBuildConfig.getCoordinatorConnectionTimeoutMillis(),
        eventBus);
  }

  /**
   * Create a {@link CoordinatorAndMinionModeRunner}.
   *
   * @param localBuildExecutor - instance of {@link BuildExecutor} object to be used by the minion.
   * @return a new instance of the {@link CoordinatorAndMinionModeRunner}.
   */
  public static CoordinatorAndMinionModeRunner createCoordinatorAndMinion(
      ListenableFuture<DelegateAndGraphs> delegateAndGraphsFuture,
      List<BuildTarget> topLevelTargets,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      Optional<BuildId> clientBuildId,
      BuildSlaveRunId buildSlaveRunId,
      ListenableFuture<BuildExecutor> localBuildExecutor,
      Path logDirectoryPath,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      MinionBuildProgressTracker minionBuildProgressTracker,
      BuckEventBus eventBus,
      ListeningExecutorService executorService,
      ArtifactCache remoteCache,
      BuildSlaveTimingStatsTracker timingStatsTracker,
      HealthCheckStatsTracker healthCheckStatsTracker,
      double coordinatorBuildCapacityRatio) {
    return new CoordinatorAndMinionModeRunner(
        createCoordinator(
            delegateAndGraphsFuture,
            topLevelTargets,
            distBuildConfig,
            distBuildService,
            stampedeId,
            clientBuildId,
            true,
            logDirectoryPath,
            coordinatorBuildRuleEventsPublisher,
            eventBus,
            executorService,
            remoteCache,
            Futures.transform(
                localBuildExecutor,
                buildExecutor -> buildExecutor.getCachingBuildEngine().getRuleKeyCalculator(),
                executorService),
            healthCheckStatsTracker,
            Optional.of(timingStatsTracker)),
        createMinion(
            localBuildExecutor,
            distBuildService,
            stampedeId,
            buildSlaveRunId,
            LOCALHOST_ADDRESS,
            OptionalInt.empty(),
            distBuildConfig,
            minionBuildProgressTracker,
            coordinatorBuildCapacityRatio,
            eventBus));
  }
}
