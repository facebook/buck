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
package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.FileMaterializationStatsTracker;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker;
import com.facebook.buck.distributed.build_slave.CoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.distributed.build_slave.HealthCheckStatsTracker;
import com.facebook.buck.distributed.build_slave.MinionBuildProgressTracker;
import com.facebook.buck.distributed.thrift.BuildRuleFinishedEvent;
import com.facebook.buck.distributed.thrift.BuildRuleStartedEvent;
import com.facebook.buck.distributed.thrift.BuildRuleUnlockedEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgressEvent;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.TimedLogger;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Listener to transmit DistBuildSlave events over to buck frontend. NOTE: We do not promise to
 * transmit every single update to BuildSlaveStatus, but we do promise to always transmit
 * BuildSlaveStatus with the latest updates.
 */
public class DistBuildSlaveEventBusListener
    implements CoordinatorBuildRuleEventsPublisher,
        MinionBuildProgressTracker,
        BuckEventListener,
        Closeable {

  private static final TimedLogger LOG =
      new TimedLogger(Logger.get(DistBuildSlaveEventBusListener.class));

  private static final int DEFAULT_SERVER_UPDATE_PERIOD_MILLIS = 500;
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

  private final StampedeId stampedeId;
  private final BuildSlaveRunId buildSlaveRunId;
  private final Clock clock;
  private final ScheduledFuture<?> scheduledServerUpdates;
  private final String hostname;

  private final Object sendServerUpdatesLock = new Object();

  private final List<BuildSlaveEvent> pendingSlaveEvents = new LinkedList<>();

  private final CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();

  private final AtomicInteger totalBuildRuleFinishedEventsSent = new AtomicInteger(0);
  private final AtomicInteger buildRulesTotalCount = new AtomicInteger(0);
  private final AtomicInteger buildRulesFinishedCount = new AtomicInteger(0);

  private final AtomicInteger buildRulesBuildingCount = new AtomicInteger(0);
  private final AtomicInteger buildRulesFailureCount = new AtomicInteger(0);

  private final RemoteCacheUploadStats remoteCacheUploadStats = new RemoteCacheUploadStats();

  private final FileMaterializationStatsTracker fileMaterializationStatsTracker;
  private final HealthCheckStatsTracker healthCheckStatsTracker;
  private final BuildSlaveTimingStatsTracker slaveStatsTracker;
  private final DistBuildMode distBuildMode;

  private volatile @Nullable CoordinatorBuildProgress coordinatorBuildProgress = null;
  private volatile @Nullable DistBuildService distBuildService;
  private volatile Optional<Integer> exitCode = Optional.empty();
  private volatile boolean sentFinishedStatsToServer;

  public DistBuildSlaveEventBusListener(
      StampedeId stampedeId,
      BuildSlaveRunId buildSlaveRunId,
      DistBuildMode distBuildMode,
      Clock clock,
      BuildSlaveTimingStatsTracker slaveStatsTracker,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      HealthCheckStatsTracker healthCheckStatsTracker,
      ScheduledExecutorService networkScheduler) {
    this(
        stampedeId,
        buildSlaveRunId,
        distBuildMode,
        clock,
        slaveStatsTracker,
        fileMaterializationStatsTracker,
        healthCheckStatsTracker,
        networkScheduler,
        DEFAULT_SERVER_UPDATE_PERIOD_MILLIS);
  }

  public DistBuildSlaveEventBusListener(
      StampedeId stampedeId,
      BuildSlaveRunId runId,
      DistBuildMode distBuildMode,
      Clock clock,
      BuildSlaveTimingStatsTracker slaveStatsTracker,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      HealthCheckStatsTracker healthCheckStatsTracker,
      ScheduledExecutorService networkScheduler,
      long serverUpdatePeriodMillis) {
    this.stampedeId = stampedeId;
    this.buildSlaveRunId = runId;
    this.clock = clock;
    this.slaveStatsTracker = slaveStatsTracker;
    this.fileMaterializationStatsTracker = fileMaterializationStatsTracker;
    this.healthCheckStatsTracker = healthCheckStatsTracker;
    this.distBuildMode = distBuildMode;

    scheduledServerUpdates =
        networkScheduler.scheduleAtFixedRate(
            this::sendServerUpdates, 0, serverUpdatePeriodMillis, TimeUnit.MILLISECONDS);

    String hostname;
    try {
      hostname = HostnameFetching.getHostname();
    } catch (IOException e) {
      hostname = "unknown";
    }
    this.hostname = hostname;
  }

  public void setDistBuildService(DistBuildService service) {
    this.distBuildService = service;
  }

  @Override
  public void outputTrace(BuildId buildId) {}

  @Override
  public void close() throws IOException {
    stopScheduledUpdates();
  }

  private BuildSlaveStatus createBuildSlaveStatus() {
    return new BuildSlaveStatus()
        .setStampedeId(stampedeId)
        .setBuildSlaveRunId(buildSlaveRunId)
        .setTotalRulesCount(buildRulesTotalCount.get())
        .setRulesFinishedCount(buildRulesFinishedCount.get())
        .setRulesBuildingCount(buildRulesBuildingCount.get())
        .setRulesFailureCount(buildRulesFailureCount.get())
        .setCacheRateStats(cacheRateStatsKeeper.getSerializableStats())
        .setHttpArtifactTotalBytesUploaded(remoteCacheUploadStats.getBytesUploaded())
        .setHttpArtifactUploadsScheduledCount(remoteCacheUploadStats.getScheduledCount())
        .setHttpArtifactUploadsOngoingCount(remoteCacheUploadStats.getOngoingCount())
        .setHttpArtifactUploadsSuccessCount(remoteCacheUploadStats.getSuccessCount())
        .setHttpArtifactUploadsFailureCount(remoteCacheUploadStats.getFailureCount())
        .setFilesMaterializedCount(
            fileMaterializationStatsTracker.getTotalFilesMaterializedCount());
  }

  private BuildSlaveFinishedStats createBuildSlaveFinishedStats() {
    BuildSlaveFinishedStats finishedStats =
        new BuildSlaveFinishedStats()
            .setHostname(hostname)
            .setDistBuildMode(distBuildMode.name())
            .setBuildSlaveStatus(createBuildSlaveStatus())
            .setFileMaterializationStats(
                fileMaterializationStatsTracker.getFileMaterializationStats())
            .setHealthCheckStats(healthCheckStatsTracker.getHealthCheckStats())
            .setBuildSlavePerStageTimingStats(slaveStatsTracker.generateStats());
    Preconditions.checkState(
        exitCode.isPresent(),
        "BuildSlaveFinishedStats can only be generated after we are finished building.");
    finishedStats.setExitCode(exitCode.get());
    return finishedStats;
  }

  private synchronized void sendFinishedStatsToFrontend(BuildSlaveFinishedStats finishedStats) {
    if (distBuildService == null || sentFinishedStatsToServer) {
      return;
    }

    try {
      distBuildService.storeBuildSlaveFinishedStats(stampedeId, buildSlaveRunId, finishedStats);
      sentFinishedStatsToServer = true;
    } catch (IOException e) {
      LOG.error(e, "Could not update slave status to frontend.");
    }
  }

  private synchronized void sendAllRulesFinishedEvent() {
    if (totalBuildRuleFinishedEventsSent.get() == 0) {
      return; // This was not the coordinator.
    }
    try {
      if (distBuildService != null) {
        distBuildService.sendAllBuildRulesPublishedEvent(
            stampedeId, buildSlaveRunId, clock.currentTimeMillis());
      }

    } catch (RuntimeException | IOException e) {
      LOG.error(e, "Failed to send slave final server updates.");
    }
  }

  private void sendStatusToFrontend() {
    if (distBuildService == null) {
      return;
    }

    try {
      distBuildService.updateBuildSlaveStatus(
          stampedeId, buildSlaveRunId, createBuildSlaveStatus());
    } catch (IOException e) {
      LOG.error(e, "Could not update slave status to frontend.");
    }
  }

  private Optional<BuildSlaveEvent> createCoordinatorBuildProgressEvent() {
    if (coordinatorBuildProgress == null) {
      return Optional.empty();
    }

    CoordinatorBuildProgressEvent progressEvent =
        new CoordinatorBuildProgressEvent().setBuildProgress(coordinatorBuildProgress);

    BuildSlaveEvent buildSlaveEvent =
        DistBuildUtil.createBuildSlaveEvent(
            BuildSlaveEventType.COORDINATOR_BUILD_PROGRESS_EVENT, clock.currentTimeMillis());
    buildSlaveEvent.setCoordinatorBuildProgressEvent(progressEvent);
    return Optional.of(buildSlaveEvent);
  }

  private void sendServerUpdates() {
    if (distBuildService == null) {
      return;
    }

    synchronized (sendServerUpdatesLock) {
      LOG.info("Sending server updates..");
      sendStatusToFrontend();

      List<BuildSlaveEvent> slaveEvents;
      synchronized (pendingSlaveEvents) {
        slaveEvents = new ArrayList<>(pendingSlaveEvents);
      }
      createCoordinatorBuildProgressEvent().ifPresent(slaveEvents::add);
      if (slaveEvents.size() == 0) {
        return;
      }

      try {
        // TODO(alisdair, shivanker): Consider batching if list is too big.
        distBuildService.uploadBuildSlaveEvents(stampedeId, buildSlaveRunId, slaveEvents);
        synchronized (pendingSlaveEvents) {
          pendingSlaveEvents.removeAll(slaveEvents);
        }
      } catch (IOException e) {
        LOG.error(e, "Failed to upload slave events.");
      }
    }
  }

  private void stopScheduledUpdates() {
    if (scheduledServerUpdates.isCancelled()) {
      return; // This method has already been called. Cancelling again will fail.
    }

    boolean cancelled = scheduledServerUpdates.cancel(false);

    if (!cancelled) {
      // Wait for the timer to shut down.
      try {
        scheduledServerUpdates.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        LOG.error(e);
      } catch (CancellationException e) {
        LOG.error(e, "Failed to call get() on scheduled executor future, as already cancelled.");
      }
    }

    // Send final updates.
    sendServerUpdates();
  }

  /** Publishes events from slave back to client that kicked off build (via frontend) */
  public void sendFinalServerUpdates(int exitCode) {
    this.exitCode = Optional.of(exitCode);
    stopScheduledUpdates();
    sendAllRulesFinishedEvent();
    sendFinishedStatsToFrontend(createBuildSlaveFinishedStats());
  }

  /** Record unexpected cache misses in build slaves. */
  @Override
  public void onUnexpectedCacheMiss(int numUnexpectedMisses) {
    cacheRateStatsKeeper.recordUnexpectedCacheMisses(numUnexpectedMisses);
  }

  @Override
  public void updateTotalRuleCount(int totalRuleCount) {
    buildRulesTotalCount.set(totalRuleCount);
  }

  @Override
  public void updateFinishedRuleCount(int finishedRuleCount) {
    buildRulesFinishedCount.set(finishedRuleCount);
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    if (!event.getLevel().equals(Level.WARNING) && !event.getLevel().equals(Level.SEVERE)) {
      return;
    }
    synchronized (pendingSlaveEvents) {
      BuildSlaveEvent slaveConsoleEvent =
          DistBuildUtil.createBuildSlaveConsoleEvent(event, clock.currentTimeMillis());
      pendingSlaveEvents.add(slaveConsoleEvent);
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    buildRulesBuildingCount.incrementAndGet();
    // For calculating the cache rate, total rule count = rules that were processed. So we increment
    // for started and resumed events, and decrement for suspended event. We do not decrement for a
    // finished event.
    cacheRateStatsKeeper.ruleCount.incrementAndGet();
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    cacheRateStatsKeeper.buildRuleFinished(finished);
    buildRulesBuildingCount.decrementAndGet();

    switch (finished.getStatus()) {
      case SUCCESS:
        break;
      case FAIL:
        buildRulesFailureCount.incrementAndGet();
        break;
      case CANCELED:
        break;
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    buildRulesBuildingCount.incrementAndGet();
    cacheRateStatsKeeper.ruleCount.incrementAndGet();
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    buildRulesBuildingCount.decrementAndGet();
    cacheRateStatsKeeper.ruleCount.decrementAndGet();
  }

  @Subscribe
  public void onHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    remoteCacheUploadStats.processScheduledEvent(event);
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    remoteCacheUploadStats.processStartedEvent(event);
  }

  @Subscribe
  public void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    remoteCacheUploadStats.processFinishedEvent(event);
  }

  @Override
  public void updateCoordinatorBuildProgress(CoordinatorBuildProgress progress) {
    coordinatorBuildProgress = progress;
  }

  @Override
  public void createBuildRuleStartedEvents(ImmutableList<String> startedTargets) {
    if (startedTargets.size() == 0) {
      return;
    }
    List<BuildSlaveEvent> ruleStartedEvents = new LinkedList<>();
    for (String target : startedTargets) {
      LOG.info(String.format("Queueing build rule started event for target [%s]", target));
      BuildRuleStartedEvent startedEvent = new BuildRuleStartedEvent();
      startedEvent.setBuildTarget(target);

      BuildSlaveEvent buildSlaveEvent =
          DistBuildUtil.createBuildSlaveEvent(
              BuildSlaveEventType.BUILD_RULE_STARTED_EVENT, clock.currentTimeMillis());
      buildSlaveEvent.setBuildRuleStartedEvent(startedEvent);
      ruleStartedEvents.add(buildSlaveEvent);
    }

    synchronized (pendingSlaveEvents) {
      pendingSlaveEvents.addAll(ruleStartedEvents);
    }
  }

  @Override
  public void createBuildRuleCompletionEvents(ImmutableList<String> finishedTargets) {
    if (finishedTargets.size() == 0) {
      return;
    }
    List<BuildSlaveEvent> ruleCompletionEvents = new LinkedList<>();
    for (String target : finishedTargets) {
      LOG.info(String.format("Queueing build rule finished event for target [%s]", target));
      BuildRuleFinishedEvent finishedEvent = new BuildRuleFinishedEvent();
      finishedEvent.setBuildTarget(target);

      BuildSlaveEvent buildSlaveEvent =
          DistBuildUtil.createBuildSlaveEvent(
              BuildSlaveEventType.BUILD_RULE_FINISHED_EVENT, clock.currentTimeMillis());
      buildSlaveEvent.setBuildRuleFinishedEvent(finishedEvent);
      ruleCompletionEvents.add(buildSlaveEvent);
    }

    synchronized (pendingSlaveEvents) {
      pendingSlaveEvents.addAll(ruleCompletionEvents);
    }
  }

  @Override
  public void createBuildRuleUnlockedEvents(ImmutableList<String> unlockedTargets) {
    if (unlockedTargets.size() == 0) {
      return;
    }
    List<BuildSlaveEvent> ruleUnlockedEvents = new LinkedList<>();
    for (String target : unlockedTargets) {
      LOG.info(String.format("Queueing build rule unlocked event for target [%s]", target));
      BuildRuleUnlockedEvent unlockedEvent = new BuildRuleUnlockedEvent();
      unlockedEvent.setBuildTarget(target);

      BuildSlaveEvent buildSlaveEvent =
          DistBuildUtil.createBuildSlaveEvent(
              BuildSlaveEventType.BUILD_RULE_UNLOCKED_EVENT, clock.currentTimeMillis());
      buildSlaveEvent.setBuildRuleUnlockedEvent(unlockedEvent);
      ruleUnlockedEvents.add(buildSlaveEvent);
    }

    synchronized (pendingSlaveEvents) {
      pendingSlaveEvents.addAll(ruleUnlockedEvents);
    }
  }

  @Override
  public void createMostBuildRulesCompletedEvent() {
    synchronized (pendingSlaveEvents) {
      pendingSlaveEvents.add(
          DistBuildUtil.createBuildSlaveEvent(
              BuildSlaveEventType.MOST_BUILD_RULES_FINISHED_EVENT, clock.currentTimeMillis()));
    }
  }
}
