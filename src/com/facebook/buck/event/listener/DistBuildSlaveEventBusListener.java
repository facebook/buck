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
import com.facebook.buck.distributed.BuildSlaveFinishedStatus;
import com.facebook.buck.distributed.BuildSlaveFinishedStatusEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import javax.annotation.concurrent.GuardedBy;

/**
 * Listener to transmit DistBuildSlave events over to buck frontend. NOTE: We do not promise to
 * transmit every single update to BuildSlaveStatus, but we do promise to always transmit
 * BuildSlaveStatus with the latest updates.
 */
public class DistBuildSlaveEventBusListener implements BuckEventListener, Closeable {

  private static final Logger LOG = Logger.get(DistBuildSlaveEventBusListener.class);

  private static final int DEFAULT_SERVER_UPDATE_PERIOD_MILLIS = 500;

  private final StampedeId stampedeId;
  private final RunId runId;
  private final Clock clock;
  private final ScheduledFuture<?> scheduledServerUpdates;

  private final Object consoleEventsLock = new Object();

  @GuardedBy("consoleEventsLock")
  private final List<BuildSlaveConsoleEvent> consoleEvents = new LinkedList<>();

  protected final CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();

  protected volatile int ruleCount = 0;
  protected final AtomicInteger buildRulesStartedCount = new AtomicInteger(0);
  protected final AtomicInteger buildRulesFinishedCount = new AtomicInteger(0);
  protected final AtomicInteger buildRulesSuccessCount = new AtomicInteger(0);
  protected final AtomicInteger buildRulesFailureCount = new AtomicInteger(0);

  protected final HttpCacheUploadStats httpCacheUploadStats = new HttpCacheUploadStats();

  private volatile @Nullable DistBuildService distBuildService;

  public DistBuildSlaveEventBusListener(
      StampedeId stampedeId, RunId runId, Clock clock, ScheduledExecutorService networkScheduler) {
    this(stampedeId, runId, clock, networkScheduler, DEFAULT_SERVER_UPDATE_PERIOD_MILLIS);
  }

  public DistBuildSlaveEventBusListener(
      StampedeId stampedeId,
      RunId runId,
      Clock clock,
      ScheduledExecutorService networkScheduler,
      long serverUpdatePeriodMillis) {
    this.stampedeId = stampedeId;
    this.runId = runId;
    this.clock = clock;

    scheduledServerUpdates =
        networkScheduler.scheduleAtFixedRate(
            this::sendServerUpdates, 0, serverUpdatePeriodMillis, TimeUnit.MILLISECONDS);
  }

  public void setDistBuildService(DistBuildService service) {
    this.distBuildService = service;
  }

  @Override
  public void outputTrace(BuildId buildId) throws InterruptedException {}

  @Override
  public void close() throws IOException {
    scheduledServerUpdates.cancel(false);
    // Send final updates.
    sendServerUpdates();
  }

  private BuildSlaveStatus createBuildSlaveStatus() {
    BuildSlaveStatus status = new BuildSlaveStatus();
    status.setStampedeId(stampedeId);
    status.setRunId(runId);

    status.setTotalRulesCount(ruleCount);
    status.setRulesStartedCount(buildRulesStartedCount.get());
    status.setRulesFinishedCount(buildRulesFinishedCount.get());
    status.setRulesSuccessCount(buildRulesSuccessCount.get());
    status.setRulesFailureCount(buildRulesFailureCount.get());

    status.setCacheRateStats(cacheRateStatsKeeper.getSerializableStats());
    status.setHttpArtifactTotalBytesUploaded(
        httpCacheUploadStats.getHttpArtifactTotalBytesUploaded());
    status.setHttpArtifactUploadsScheduledCount(
        httpCacheUploadStats.getHttpArtifactTotalUploadsScheduledCount());
    status.setHttpArtifactUploadsOngoingCount(
        httpCacheUploadStats.getHttpArtifactUploadsOngoingCount());
    status.setHttpArtifactUploadsSuccessCount(
        httpCacheUploadStats.getHttpArtifactUploadsSuccessCount());
    status.setHttpArtifactUploadsFailureCount(
        httpCacheUploadStats.getHttpArtifactUploadsFailureCount());

    return status;
  }

  private BuildSlaveFinishedStatus createBuildSlaveFinishedStatus(int exitCode) {
    return BuildSlaveFinishedStatus.builder()
        .setStampedeId(stampedeId)
        .setRunId(runId)
        .setTotalRulesCount(ruleCount)
        .setRulesStartedCount(buildRulesStartedCount.get())
        .setRulesFinishedCount(buildRulesFinishedCount.get())
        .setRulesSuccessCount(buildRulesSuccessCount.get())
        .setRulesFailureCount(buildRulesFailureCount.get())
        .setCacheRateStats(cacheRateStatsKeeper.getSerializableStats())
        .setExitCode(exitCode)
        .build();
  }

  private void sendStatusToFrontend() {
    if (distBuildService == null) {
      return;
    }

    try {
      distBuildService.updateBuildSlaveStatus(stampedeId, runId, createBuildSlaveStatus());
    } catch (IOException e) {
      LOG.error(e, "Could not update slave status to frontend.");
    }
  }

  private void sendConsoleEventsToFrontend() {
    if (distBuildService == null) {
      return;
    }

    ImmutableList<BuildSlaveConsoleEvent> consoleEventsCopy;
    synchronized (consoleEventsLock) {
      consoleEventsCopy = ImmutableList.copyOf(consoleEvents);
      consoleEvents.clear();
    }

    if (consoleEventsCopy.size() == 0) {
      return;
    }

    try {
      distBuildService.uploadBuildSlaveConsoleEvents(stampedeId, runId, consoleEventsCopy);
    } catch (IOException e) {
      LOG.error(e, "Could not upload slave console events to frontend.");
    }
  }

  private void sendServerUpdates() {
    sendStatusToFrontend();
    sendConsoleEventsToFrontend();
  }

  public void publishBuildSlaveFinishedEvent(BuckEventBus eventBus, int exitCode) {
    eventBus.post(new BuildSlaveFinishedStatusEvent(createBuildSlaveFinishedStatus(exitCode)));
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    if (!event.getLevel().equals(Level.WARNING) && !event.getLevel().equals(Level.SEVERE)) {
      return;
    }
    synchronized (consoleEventsLock) {
      BuildSlaveConsoleEvent slaveConsoleEvent =
          DistBuildUtil.createBuildSlaveConsoleEvent(event, clock.currentTimeMillis());
      consoleEvents.add(slaveConsoleEvent);
    }
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    cacheRateStatsKeeper.ruleCountCalculated(calculated);
    ruleCount = calculated.getNumRules();
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    cacheRateStatsKeeper.ruleCountUpdated(updated);
    ruleCount = updated.getNumRules();
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    buildRulesStartedCount.incrementAndGet();
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    cacheRateStatsKeeper.buildRuleFinished(finished);
    buildRulesStartedCount.decrementAndGet();
    buildRulesFinishedCount.incrementAndGet();

    switch (finished.getStatus()) {
      case SUCCESS:
        buildRulesSuccessCount.incrementAndGet();
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
    buildRulesStartedCount.incrementAndGet();
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    buildRulesStartedCount.decrementAndGet();
  }

  @Subscribe
  public void onHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    httpCacheUploadStats.processHttpArtifactCacheScheduledEvent(event);
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    httpCacheUploadStats.processHttpArtifactCacheStartedEvent(event);
  }

  @Subscribe
  public void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    httpCacheUploadStats.processHttpArtifactCacheFinishedEvent(event);
  }
}
