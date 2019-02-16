/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.event.listener.stats.stampede;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.distributed.build_client.DistBuildRemoteProgressEvent;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import java.util.Optional;
import org.junit.Test;

public class DistBuildStatsTrackerTest {
  @Test
  public void testApproximateDistBuildProgressDoesNotLosePrecision() {
    DistBuildStatsTracker tracker = new DistBuildStatsTracker();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(tracker);

    eventBus.post(progressEvent(100, 0, 0));
    assertEquals(Optional.of(0.0), tracker.getApproximateProgress());

    eventBus.post(progressEvent(100, 2, 0));
    assertEquals(Optional.of(0.02), tracker.getApproximateProgress());

    eventBus.post(progressEvent(100, 4, 83));
    assertEquals(Optional.of(0.23), tracker.getApproximateProgress());
  }

  private BuckEvent progressEvent(int totalRules, int builtRules, int skippedRules) {
    CoordinatorBuildProgress coordinatorProgress = new CoordinatorBuildProgress();
    coordinatorProgress.setTotalRulesCount(totalRules);
    coordinatorProgress.setBuiltRulesCount(builtRules);
    coordinatorProgress.setSkippedRulesCount(skippedRules);
    return new DistBuildRemoteProgressEvent(coordinatorProgress);
  }
}
