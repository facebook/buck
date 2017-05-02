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

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
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

  private final Object statusLock = new Object();

  @GuardedBy("statusLock")
  private final BuildSlaveStatus status = new BuildSlaveStatus();

  protected final CacheRateStatsKeeper cacheRateStatsKeeper = new CacheRateStatsKeeper();

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

    // No synchronization needed here because status is final.
    status.setStampedeId(stampedeId);
    status.setRunId(runId);
    // Set the cache rate stats with zero values, so that we don't have to keep checking for nulls.
    status.setCacheRateStats(cacheRateStatsKeeper.getSerializableStats());

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

  private void sendStatusToFrontend() {
    if (distBuildService == null) {
      return;
    }

    BuildSlaveStatus statusCopy;
    synchronized (statusLock) {
      statusCopy = status.deepCopy();
    }
    try {
      distBuildService.updateBuildSlaveStatus(stampedeId, runId, statusCopy);
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
    synchronized (statusLock) {
      status.setTotalRulesCount(calculated.getNumRules());
      status.setCacheRateStats(cacheRateStatsKeeper.getSerializableStats());
    }
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    cacheRateStatsKeeper.ruleCountUpdated(updated);
    synchronized (statusLock) {
      status.setTotalRulesCount(updated.getNumRules());
      status.setCacheRateStats(cacheRateStatsKeeper.getSerializableStats());
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    synchronized (statusLock) {
      status.setRulesStartedCount(status.getRulesStartedCount() + 1);
    }
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    cacheRateStatsKeeper.buildRuleFinished(finished);
    synchronized (statusLock) {
      status.setRulesStartedCount(status.getRulesStartedCount() - 1);
      status.setRulesFinishedCount(status.getRulesFinishedCount() + 1);

      switch (finished.getStatus()) {
        case SUCCESS:
          status.setRulesSuccessCount(status.getRulesSuccessCount() + 1);
          break;
        case FAIL:
          status.setRulesFailureCount(status.getRulesFailureCount() + 1);
          break;
        case CANCELED:
          break;
      }

      status.setCacheRateStats(cacheRateStatsKeeper.getSerializableStats());
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    synchronized (statusLock) {
      status.setRulesStartedCount(status.getRulesStartedCount() + 1);
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    synchronized (statusLock) {
      status.setRulesStartedCount(status.getRulesStartedCount() - 1);
    }
  }

  /**
   * TODO(shivanker): Add support for keeping track of cache uploads. @Subscribe public void
   * onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {}
   */
}
