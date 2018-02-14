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

import com.facebook.buck.distributed.thrift.HealthCheckStats;

/** Used to keep track of how long minion health checks are taking, as observed by coordinator. */
public class HealthCheckStatsTracker {
  private int slowHeartbeatsCount = 0;
  private long slowestHeartbeatIntervalMillis = 0;
  private long slowestDeadMinionCheckIntervalMillis = 0;
  private int slowDeadMinionChecksCount = 0;
  private String slowestHeartbeatMinionId = "";
  private int totalHeartbeats = 0;
  private long totalTimeBetweenHeartbeatsMillis = 0;

  /**
   * Notify stats tracker that a new heartbeat was received.
   *
   * @param minionId
   * @param elapsedTimeSinceLastHeartbeatMillis
   * @param wasTooSlow
   */
  public synchronized void recordHeartbeatSample(
      String minionId, long elapsedTimeSinceLastHeartbeatMillis, boolean wasTooSlow) {
    totalHeartbeats++;
    totalTimeBetweenHeartbeatsMillis += elapsedTimeSinceLastHeartbeatMillis;

    if (wasTooSlow) {
      slowHeartbeatsCount++;
    }

    if (elapsedTimeSinceLastHeartbeatMillis > slowestHeartbeatIntervalMillis) {
      slowestHeartbeatIntervalMillis = elapsedTimeSinceLastHeartbeatMillis;
      slowestHeartbeatMinionId = minionId;
    }
  }

  /**
   * Notify stats tracker about a new dead minion check completing.
   *
   * @param elapsedTimeSinceLastCheckMillis
   * @param wasTooSlow
   */
  public synchronized void recordDeadMinionCheckSample(
      long elapsedTimeSinceLastCheckMillis, boolean wasTooSlow) {
    if (wasTooSlow) {
      slowDeadMinionChecksCount++;
    }

    if (elapsedTimeSinceLastCheckMillis > slowestDeadMinionCheckIntervalMillis) {
      slowestDeadMinionCheckIntervalMillis = elapsedTimeSinceLastCheckMillis;
    }
  }

  /**
   * Take all recorded samples and produce a HealthCheckStats.
   *
   * @return
   */
  public synchronized HealthCheckStats getHealthCheckStats() {
    long averageHealthCheckIntervalMillis =
        (totalHeartbeats > 0) ? (totalTimeBetweenHeartbeatsMillis / totalHeartbeats) : 0;

    HealthCheckStats healthCheckStats = new HealthCheckStats();
    healthCheckStats.setSlowHeartbeatsReceivedCount(slowHeartbeatsCount);
    healthCheckStats.setHeartbeatsReceivedCount(totalHeartbeats);
    healthCheckStats.setAverageHeartbeatIntervalMillis(averageHealthCheckIntervalMillis);
    healthCheckStats.setSlowestHeartbeatIntervalMillis(slowestHeartbeatIntervalMillis);
    healthCheckStats.setSlowestHeartbeatMinionId(slowestHeartbeatMinionId);
    healthCheckStats.setSlowDeadMinionChecksCount(slowDeadMinionChecksCount);
    healthCheckStats.setSlowestDeadMinionCheckIntervalMillis(slowestDeadMinionCheckIntervalMillis);

    return healthCheckStats;
  }
}
