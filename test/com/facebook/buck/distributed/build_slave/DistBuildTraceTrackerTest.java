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

import com.facebook.buck.distributed.build_slave.DistBuildTrace.MinionThread;
import com.facebook.buck.distributed.build_slave.DistBuildTrace.MinionTrace;
import com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.util.timing.SettableFakeClock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class DistBuildTraceTrackerTest {

  @Test
  public void testWorkUnit() {
    long ts0 = Instant.parse("2017-10-24T15:55:40.123Z").toEpochMilli();

    StampedeId stampedeId = new StampedeId();
    stampedeId.id = "aaa";
    SettableFakeClock clock = new SettableFakeClock(ts0 + 1000, 0);
    DistBuildTraceTracker tracker = new DistBuildTraceTracker(stampedeId, clock);

    WorkUnit workUnit = new WorkUnit();
    workUnit.buildTargets = Arrays.asList("aaa", "bbb");
    tracker.updateWork("m1", Collections.emptyList(), Collections.singletonList(workUnit));

    clock.setCurrentTimeMillis(ts0 + 2000);

    tracker.updateWork("m1", Collections.singletonList("aaa"), Collections.emptyList());

    clock.setCurrentTimeMillis(ts0 + 3000);

    tracker.updateWork("m1", Collections.singletonList("bbb"), Collections.emptyList());

    DistBuildTrace trace = tracker.generateTrace();
    Assert.assertEquals(1, trace.minions.size());
    MinionTrace historyForMinion1 = trace.minions.get(0);
    Assert.assertEquals(1, historyForMinion1.threads.size());
    MinionThread historyForThread1 = historyForMinion1.threads.get(0);
    Assert.assertEquals(2, historyForThread1.ruleTraces.size());

    RuleTrace entry0 = historyForThread1.ruleTraces.get(0);
    Assert.assertEquals("aaa", entry0.ruleName);
    Assert.assertEquals(ts0 + 1000, entry0.startEpochMillis);
    Assert.assertEquals(ts0 + 2000, entry0.finishEpochMillis);

    RuleTrace entry1 = historyForThread1.ruleTraces.get(1);
    Assert.assertEquals("bbb", entry1.ruleName);
    Assert.assertEquals(ts0 + 2000, entry1.startEpochMillis);
    Assert.assertEquals(ts0 + 3000, entry1.finishEpochMillis);
  }
}
