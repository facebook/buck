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
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildArtifactCacheImpl;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
      Optional<BuildSlaveTimingStatsTracker> timingStatsTracker,
      Optional<String> coordinatorMinionId) {

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
                              graphs.getActionGraphAndBuilder().getActionGraphBuilder(),
                              executorService,
                              remoteCache,
                              eventBus,
                              ruleKeyCalculator,
                              Optional.empty())) {
                        queue =
                            new CacheOptimizedBuildTargetsQueueFactory(
                                    graphs.getActionGraphAndBuilder().getActionGraphBuilder(),
                                    artifactCache,
                                    distBuildConfig.isDeepRemoteBuildEnabled(),
                                    ruleKeyCalculator.getRuleDepsCache(),
                                    distBuildConfig.shouldBuildSelectedTargetsLocally())
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

    MinionQueueProvider minionQueueProvider =
        createMinionQueueProvider(distBuildConfig.getLowSpecMinionQueue(), minionQueue.get());

    CoordinatorEventListener listenerAndMinionCountProvider =
        new CoordinatorEventListener(
            distBuildService,
            stampedeId,
            distBuildConfig.getBuildLabel(),
            minionQueueProvider,
            isLocalMinionAlsoRunning);
    MinionHealthTracker minionHealthTracker =
        new MinionHealthTracker(
            new DefaultClock(),
            distBuildConfig.getMaxMinionSilenceMillis(),
            distBuildConfig.getMaxConsecutiveSlowDeadMinionChecks(),
            distBuildConfig.getHeartbeatServiceRateMillis(),
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
        listenerAndMinionCountProvider,
        coordinatorMinionId,
        distBuildConfig.isReleasingMinionsEarlyEnabled());
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
      MinionType minionType,
      CapacityService capacityService,
      BuildSlaveRunId buildSlaveRunId,
      String coordinatorAddress,
      OptionalInt coordinatorPort,
      DistBuildConfig distBuildConfig,
      MinionBuildProgressTracker minionBuildProgressTracker,
      BuckEventBus eventBus) {
    MinionModeRunner.BuildCompletionChecker checker =
        () -> {
          BuildJob job = distBuildService.getCurrentBuildJobState(stampedeId);
          return BuildStatusUtil.isTerminalBuildStatus(job.getStatus());
        };

    // Check if coordinator and minion are stacked in the same host and
    // update coordinator address to localhost if that's the case.
    try {
      if (coordinatorAddress.equals(InetAddress.getLocalHost().getHostName())) {
        coordinatorAddress = LOCALHOST_ADDRESS;
      }
    } catch (UnknownHostException e) {
      LOG.error("Hostname can not be resolved");
    }

    return new MinionModeRunner(
        coordinatorAddress,
        coordinatorPort,
        localBuildExecutor,
        stampedeId,
        minionType,
        buildSlaveRunId,
        createCapacityTracker(capacityService, distBuildConfig),
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
      CapacityService capacityService,
      BuildSlaveRunId buildSlaveRunId,
      ListenableFuture<BuildExecutor> localBuildExecutor,
      Path logDirectoryPath,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      MinionBuildProgressTracker minionBuildProgressTracker,
      BuckEventBus eventBus,
      ListeningExecutorService executorService,
      ArtifactCache remoteCache,
      BuildSlaveTimingStatsTracker timingStatsTracker,
      HealthCheckStatsTracker healthCheckStatsTracker) {
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
            Optional.of(timingStatsTracker),
            Optional.of(DistBuildUtil.generateMinionId(buildSlaveRunId))),
        createMinion(
            localBuildExecutor,
            distBuildService,
            stampedeId,
            MinionType.STANDARD_SPEC, // Coordinator should always run on standard spec machine.
            capacityService,
            buildSlaveRunId,
            LOCALHOST_ADDRESS,
            OptionalInt.empty(),
            distBuildConfig,
            minionBuildProgressTracker,
            eventBus));
  }

  /** @return MinionQueueProvider populated with standard queue, and optionally low spec queue. */
  private static MinionQueueProvider createMinionQueueProvider(
      Optional<String> lowSpecMinionQueue, String standardSpecMinionQueue) {
    MinionQueueProvider queueProvider = new MinionQueueProvider();

    if (lowSpecMinionQueue.isPresent()) {
      queueProvider.registerMinionQueue(MinionType.LOW_SPEC, lowSpecMinionQueue.get());
    }

    queueProvider.registerMinionQueue(MinionType.STANDARD_SPEC, standardSpecMinionQueue);

    return queueProvider;
  }

  private static CapacityTracker createCapacityTracker(
      CapacityService service, DistBuildConfig distBuildConfig) {
    int availableBuildCapacity =
        distBuildConfig
            .getBuckConfig()
            .getView(ResourcesConfig.class)
            .getConcurrencyLimit()
            .threadLimit;

    // We always need more than 1 core to make progress.
    availableBuildCapacity = Math.max(1, availableBuildCapacity);

    // For stacked builds (size > 1) we want to communicate with the build slave which
    // coordinates multiple minion processes and keeps track of available cores.
    if (distBuildConfig.getStackSize() > 1) {
      if (distBuildConfig.isGreedyStackingEnabled()) {
        LOG.info("Creating GreedyMultiBuildCapacityTracker");
        return new GreedyMultiBuildCapacityTracker(service, availableBuildCapacity);
      }
      LOG.info("Creating DefaultMultiBuildCapacityTracker");
      return new DefaultMultiBuildCapacityTracker(service, availableBuildCapacity);
    }

    // Otherwise we use have a single minion running on the host
    // which can use all the available threads.
    LOG.info("Creating SingleBuildCapacityTracker");
    return new SingleBuildCapacityTracker(availableBuildCapacity);
  }
}
