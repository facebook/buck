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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks the health of Minions to make sure they don't silently die and the build hangs. */
public class MinionHealthTracker {
  private static final Logger LOG = Logger.get(MinionHealthTracker.class);

  private final Map<String, MinionTrackingInfo> minions;
  private final Set<String> untrackedMinions;
  private final Clock clock;
  private final long maxMinionSilenceMillis;
  private final long slowHeartbeatWarningThresholdMillis;

  private long lastDeadMinionCheckMillis = -1;

  private final HealthCheckStatsTracker healthCheckStatsTracker;

  public MinionHealthTracker(
      Clock clock,
      long maxMinionSilenceMillis,
      long expectedHeartbeatIntervalMillis,
      long slowHeartbeatWarningThresholdMillis,
      HealthCheckStatsTracker healthCheckStatsTracker) {
    this.healthCheckStatsTracker = healthCheckStatsTracker;
    Preconditions.checkArgument(
        slowHeartbeatWarningThresholdMillis > expectedHeartbeatIntervalMillis,
        "slow_heartbeat_warning_threshold_millis must be higher than heartbeat_service_interval_millis");
    Preconditions.checkArgument(
        maxMinionSilenceMillis > 0,
        "The maxMinionSilenceMillis value needs to be positive. Found [%d] instead.",
        maxMinionSilenceMillis);
    this.maxMinionSilenceMillis = maxMinionSilenceMillis;
    this.slowHeartbeatWarningThresholdMillis = slowHeartbeatWarningThresholdMillis;
    this.minions = Maps.newConcurrentMap();
    this.untrackedMinions = Sets.newConcurrentHashSet();
    this.clock = clock;
  }

  /** Heartbeat reports a minion is currently alive and happily running. */
  public void reportMinionAlive(String minionId) {
    LOG.debug(String.format("Received keep alive heartbeat from Minion [%s]", minionId));
    minions.computeIfAbsent(minionId, key -> new MinionTrackingInfo(minionId)).reportHealthy();
  }

  /** Returns all minions that are currently thought to be dead/not-healthy. */
  public List<String> getDeadMinions() {
    boolean firstRun = lastDeadMinionCheckMillis == -1;

    List<String> deadMinionIds = Lists.newArrayList();
    long currentMillis = clock.currentTimeMillis();
    long timeSinceLastDeadMinionCheck = currentMillis - lastDeadMinionCheckMillis;

    for (MinionTrackingInfo minion : minions.values()) {
      if (untrackedMinions.contains(minion.getMinionId())) {
        continue;
      }

      long lastHeartbeatMillis = minion.getLastHeartbeatMillis();
      long timeSinceLastHeartbeatMillis = currentMillis - lastHeartbeatMillis;
      if (timeSinceLastHeartbeatMillis > maxMinionSilenceMillis) {
        LOG.error(
            String.format(
                "Minion [%s] failed healthcheck. Marking as dead. Last heartbeat ts [%d]. Current ts [%d].",
                minion.minionId, lastHeartbeatMillis, currentMillis));
        deadMinionIds.add(minion.getMinionId());
      }

      if (timeSinceLastHeartbeatMillis > slowHeartbeatWarningThresholdMillis) {
        LOG.warn(
            String.format(
                "Minion [%s] took [%d] ms to send heartbeat. This is higher than warning threshold [%d] ms. Last heartbeat ts [%d]. Current ts [%d].",
                minion.minionId,
                timeSinceLastHeartbeatMillis,
                slowHeartbeatWarningThresholdMillis,
                lastHeartbeatMillis,
                currentMillis));
      }
    }

    boolean deadMinionCheckWasSlow =
        timeSinceLastDeadMinionCheck > slowHeartbeatWarningThresholdMillis;
    if (!firstRun && deadMinionCheckWasSlow) {
      LOG.warn(
          String.format(
              "Coordinator took [%d] ms to run dead minion check. This is higher than warning threshold [%d] ms. Last check ts [%d]. Current check ts [%d].",
              timeSinceLastDeadMinionCheck,
              slowHeartbeatWarningThresholdMillis,
              lastDeadMinionCheckMillis,
              currentMillis));
    }

    if (!firstRun) {
      healthCheckStatsTracker.recordDeadMinionCheckSample(
          timeSinceLastDeadMinionCheck, deadMinionCheckWasSlow);
    }

    lastDeadMinionCheckMillis = currentMillis;

    return deadMinionIds;
  }

  /**
   * Tell the tracker it is fine to forever stop tracking this minion. This class is thread-safe.
   */
  public void stopTrackingForever(String minionId) {
    untrackedMinions.add(minionId);
  }

  private class MinionTrackingInfo {
    private final String minionId;
    private long lastHeartbeatMillis = -1;

    public MinionTrackingInfo(String minionId) {
      this.minionId = minionId;
    }

    public synchronized void reportHealthy() {
      boolean firstRun = lastHeartbeatMillis == -1;
      long currentHealthCheckMillis = clock.currentTimeMillis();
      long elapsedTimeSinceLastHeartbeat = currentHealthCheckMillis - lastHeartbeatMillis;

      boolean heartbeatWasSlow =
          elapsedTimeSinceLastHeartbeat >= slowHeartbeatWarningThresholdMillis;
      if (!firstRun && heartbeatWasSlow) {
        LOG.warn(
            String.format(
                "Minion [%s] took [%d] to send heartbeat. This is higher than warning threshold [%d]. Last heartbeat ts [%d]. Current ts [%d].",
                minionId,
                elapsedTimeSinceLastHeartbeat,
                slowHeartbeatWarningThresholdMillis,
                lastHeartbeatMillis,
                currentHealthCheckMillis));
      }

      lastHeartbeatMillis = currentHealthCheckMillis;
      LOG.debug(
          String.format(
              "Updated keep alive ts for Minion [%s] to [%d]", minionId, lastHeartbeatMillis));

      if (!firstRun) {
        healthCheckStatsTracker.recordHeartbeatSample(
            minionId, elapsedTimeSinceLastHeartbeat, heartbeatWasSlow);
      }

      firstRun = false;
    }

    public synchronized long getLastHeartbeatMillis() {
      return lastHeartbeatMillis;
    }

    public String getMinionId() {
      return minionId;
    }
  }
}
