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

import com.facebook.buck.util.timing.Clock;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MinionHealthTrackerTest {

  private static final long MAX_SILENCE_MILLIS = 42;
  private static final String MINION_ONE = "super minion number one";
  private static final String MINION_TWO = "average minion number two";
  private static final String MINION_THREE = "no so cool minion number three";

  private SettableClock clock;
  private MinionHealthTracker tracker;

  @Before
  public void setUp() {
    clock = new SettableClock();
    tracker = new MinionHealthTracker(clock, MAX_SILENCE_MILLIS);
  }

  @Test
  public void testDeadMinionsCheckWithNoMinions() {
    List<String> deadMinions = tracker.getDeadMinions();
    Assert.assertNotNull(deadMinions);
    Assert.assertTrue(deadMinions.isEmpty());
  }

  @Test
  public void testDeadMinionsCheckWith2DeadMinions() {
    tracker.reportMinionAlive(MINION_ONE);
    tracker.reportMinionAlive(MINION_TWO);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    tracker.reportMinionAlive(MINION_THREE);
    List<String> deadMinions = tracker.getDeadMinions();
    Assert.assertEquals(2, deadMinions.size());
    Assert.assertTrue(deadMinions.contains(MINION_ONE));
    Assert.assertTrue(deadMinions.contains(MINION_TWO));
  }

  @Test
  public void testDeadMinionsCheckWithNoDeadMinions() {
    tracker.reportMinionAlive(MINION_ONE);
    tracker.reportMinionAlive(MINION_TWO);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS);
    tracker.reportMinionAlive(MINION_THREE);
    List<String> deadMinions = tracker.getDeadMinions();
    Assert.assertTrue(deadMinions.isEmpty());
  }

  @Test
  public void testReportingAndRemovingMinion() {
    tracker.reportMinionAlive(MINION_ONE);
    List<String> deadMinions = tracker.getDeadMinions();
    Assert.assertTrue(deadMinions.isEmpty());

    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    deadMinions = tracker.getDeadMinions();
    Assert.assertEquals(1, deadMinions.size());
    Assert.assertEquals(MINION_ONE, deadMinions.get(0));

    tracker.stopTrackingForever(MINION_ONE);
    Assert.assertTrue(tracker.getDeadMinions().isEmpty());
  }

  @Test
  public void testStopTrackingUnexistentMinionIsFine() {
    // Should not throw.
    tracker.stopTrackingForever(MINION_ONE);
    Assert.assertNotNull(tracker.getDeadMinions());
  }

  @Test
  public void testMultipleReportsOverwritePrevioustate() {
    tracker.reportMinionAlive(MINION_ONE);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    Assert.assertEquals(1, tracker.getDeadMinions().size());

    tracker.reportMinionAlive(MINION_ONE);
    Assert.assertEquals(0, tracker.getDeadMinions().size());
  }

  @Test
  public void testUntrackedMinionWillNeverBeMarkedDeaD() {
    tracker.reportMinionAlive(MINION_ONE);
    tracker.stopTrackingForever(MINION_ONE);
    clock.setCurrentMillis(MAX_SILENCE_MILLIS + 1);
    Assert.assertTrue(tracker.getDeadMinions().isEmpty());

    tracker.reportMinionAlive(MINION_ONE);
    clock.setCurrentMillis(clock.currentMillis + MAX_SILENCE_MILLIS + 1);
    Assert.assertTrue(tracker.getDeadMinions().isEmpty());
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
