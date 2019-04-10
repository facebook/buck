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
import com.facebook.buck.util.timing.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MinionHealthTrackerTest {

  private static final long MAX_SILENCE_MILLIS = 30;
  private static final int MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS = 2;
  private static final String MINION_ONE = "super minion number one";
  private static final String MINION_TWO = "average minion number two";
  private static final String MINION_THREE = "no so cool minion number three";

  private static final long EXPECTED_HEARTBEAT_INTERVAL_MILLIS = 10;
  private static final long SLOW_HEARTBEAT_WARNING_THRESHOlD_MILLIS = 20;

  private SettableClock clock;
  private MinionHealthTracker tracker;
  private HealthCheckStatsTracker healthCheckStatsTracker;

  @Before
  public void setUp() {
    clock = new SettableClock();
    healthCheckStatsTracker = new HealthCheckStatsTracker();
    tracker =
        new MinionHealthTracker(
            clock,
            MAX_SILENCE_MILLIS,
            MAX_CONSECUTIVE_SLOW_DEAD_MINION_CHECKS,
            EXPECTED_HEARTBEAT_INTERVAL_MILLIS,
            SLOW_HEARTBEAT_WARNING_THRESHOlD_MILLIS,
            healthCheckStatsTracker);
  }

  @Test
  public void testDeadMinionsCheckWithNoMinions() {
    List<String> deadMinions = getDeadMinions();
    Assert.assertNotNull(deadMinions);
    Assert.assertTrue(deadMinions.isEmpty());
  }

  @Test
  public void testSlowHeartbeatRecordedInStats() {
    clock.setCurrentMillis(1);
    tracker.checkMinionHealth();
    tracker.checkMinionHealth();
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE); // Doesn't count, just initializes
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    clock.setCurrentMillis(SLOW_HEARTBEAT_WARNING_THRESHOlD_MILLIS + 1);
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);

    // Check health stats
    HealthCheckStats healthCheckStats = healthCheckStatsTracker.getHealthCheckStats();
    Assert.assertEquals(2, healthCheckStats.getHeartbeatsReceivedCount());
    Assert.assertEquals(1, healthCheckStats.getSlowHeartbeatsReceivedCount());
    Assert.assertEquals(MINION_ONE, healthCheckStats.getSlowestHeartbeatMinionId());
    Assert.assertEquals(0, healthCheckStats.getSlowDeadMinionChecksCount());
    Assert.assertEquals(0, healthCheckStats.getSlowestDeadMinionCheckIntervalMillis());
    Assert.assertEquals(20, healthCheckStats.getSlowestHeartbeatIntervalMillis());

    // First real health check took 0 millis. Second one took 20 millis. => 10 mills average
    Assert.assertEquals(10, healthCheckStats.getAverageHeartbeatIntervalMillis());

    tracker.reportMinionAlive(MINION_TWO, MINION_TWO); // Doesn't count, just initializes
    clock.setCurrentMillis(clock.currentMillis + EXPECTED_HEARTBEAT_INTERVAL_MILLIS);
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    tracker.reportMinionAlive(MINION_TWO, MINION_TWO);
    tracker.checkMinionHealth();

    // Check updated health stats
    healthCheckStats = healthCheckStatsTracker.getHealthCheckStats();
    Assert.assertEquals(4, healthCheckStats.getHeartbeatsReceivedCount());
    Assert.assertEquals(1, healthCheckStats.getSlowHeartbeatsReceivedCount());
    Assert.assertEquals(MINION_ONE, healthCheckStats.getSlowestHeartbeatMinionId());
    Assert.assertEquals(1, healthCheckStats.getSlowDeadMinionChecksCount());
    Assert.assertEquals(30, healthCheckStats.getSlowestDeadMinionCheckIntervalMillis());
    Assert.assertEquals(20, healthCheckStats.getSlowestHeartbeatIntervalMillis());

    // All heartbeats since last time were 10 more seconds, so average should stay the same.
    Assert.assertEquals(10, healthCheckStats.getAverageHeartbeatIntervalMillis());
  }

  @Test
  public void testDeadMinionsCheckWith2DeadMinions() {
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    tracker.reportMinionAlive(MINION_TWO, MINION_TWO);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    tracker.reportMinionAlive(MINION_THREE, MINION_THREE);
    List<String> deadMinions = getDeadMinions();
    Assert.assertEquals(2, deadMinions.size());
    Assert.assertTrue(deadMinions.contains(MINION_ONE));
    Assert.assertTrue(deadMinions.contains(MINION_TWO));
  }

  @Test
  public void testDeadMinionsCheckWithNoDeadMinions() {
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    tracker.reportMinionAlive(MINION_TWO, MINION_TWO);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS);
    tracker.reportMinionAlive(MINION_THREE, MINION_THREE);
    List<String> deadMinions = getDeadMinions();
    Assert.assertTrue(deadMinions.isEmpty());
  }

  @Test
  public void testDeadMinionCheckSkippedWhenCoordinatorSlow() {
    tracker.checkMinionHealth();
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);

    clock.setCurrentMillis(clock.currentTimeMillis() + MAX_SILENCE_MILLIS);
    Assert.assertEquals(0, tracker.checkMinionHealth().getDeadMinions().size());
  }

  @Test
  public void testDeadMinionCheckWhenCoordinatorSlowMultipleTimes() {
    tracker.checkMinionHealth();
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);

    clock.setCurrentMillis(clock.currentTimeMillis() + MAX_SILENCE_MILLIS);
    Assert.assertEquals(0, tracker.checkMinionHealth().getDeadMinions().size());

    clock.setCurrentMillis(clock.currentTimeMillis() + MAX_SILENCE_MILLIS);
    Assert.assertEquals(1, tracker.checkMinionHealth().getDeadMinions().size());
  }

  @Test
  public void testMinionThatReportsHealthIsNotDeadAfterSlowCoordinatorCheck() {
    tracker.checkMinionHealth();
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);

    clock.setCurrentMillis(clock.currentTimeMillis() + MAX_SILENCE_MILLIS);
    Assert.assertEquals(0, tracker.checkMinionHealth().getDeadMinions().size());

    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    clock.setCurrentMillis(clock.currentTimeMillis() + EXPECTED_HEARTBEAT_INTERVAL_MILLIS);
    Assert.assertEquals(0, tracker.checkMinionHealth().getDeadMinions().size());
  }

  @Test
  public void testReportingAndRemovingMinion() {
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    List<String> deadMinions = getDeadMinions();
    Assert.assertTrue(deadMinions.isEmpty());

    clock.setCurrentMillis(clock.currentTimeMillis() + EXPECTED_HEARTBEAT_INTERVAL_MILLIS);
    Assert.assertEquals(0, getDeadMinions().size());

    clock.setCurrentMillis(clock.currentTimeMillis() + EXPECTED_HEARTBEAT_INTERVAL_MILLIS);
    Assert.assertEquals(0, getDeadMinions().size());

    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    deadMinions = getDeadMinions();
    Assert.assertEquals(1, deadMinions.size());
    Assert.assertEquals(MINION_ONE, deadMinions.get(0));

    tracker.stopTrackingForever(MINION_ONE);
    Assert.assertTrue(tracker.checkMinionHealth().getDeadMinions().isEmpty());
  }

  @Test
  public void testStopTrackingUnexistentMinionIsFine() {
    // Should not throw.
    tracker.stopTrackingForever(MINION_ONE);
    Assert.assertNotNull(tracker.checkMinionHealth());
  }

  @Test
  public void testMultipleReportsOverwritePrevioustate() {
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    Assert.assertEquals(1, tracker.checkMinionHealth().getDeadMinions().size());

    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    Assert.assertEquals(0, tracker.checkMinionHealth().getDeadMinions().size());
  }

  @Test
  public void testUntrackedMinionWillNeverBeMarkedDeaD() {
    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    tracker.stopTrackingForever(MINION_ONE);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    Assert.assertTrue(tracker.checkMinionHealth().getDeadMinions().isEmpty());

    tracker.reportMinionAlive(MINION_ONE, MINION_ONE);
    clock.setCurrentMillis(clock.currentMillis + MAX_SILENCE_MILLIS + 1);
    Assert.assertTrue(tracker.checkMinionHealth().getDeadMinions().isEmpty());
  }

  private final List<String> getDeadMinions() {
    return tracker.checkMinionHealth().getDeadMinions().stream()
        .map(m -> m.getMinionId())
        .collect(Collectors.toList());
  }

  private static class SettableClock implements Clock {

    private long currentMillis;

    public SettableClock() {
      currentMillis = 0;
    }

    public void setCurrentMillis(long currentMillis) {
      this.currentMillis = currentMillis;
    }

    @Override
    public long currentTimeMillis() {
      return currentMillis;
    }

    @Override
    public long nanoTime() {
      throw new RuntimeException("not implemented");
    }

    @Override
    public long threadUserNanoTime(long threadId) {
      throw new RuntimeException("not implemented");
    }
  }
}
