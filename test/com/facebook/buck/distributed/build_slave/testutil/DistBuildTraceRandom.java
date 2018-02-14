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

package com.facebook.buck.distributed.build_slave.testutil;

import com.facebook.buck.distributed.build_slave.DistBuildTrace;
import com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace;
import com.facebook.buck.distributed.thrift.StampedeId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Generate random {@link DistBuildTrace} object.
 *
 * <p>To be used in tests or in examples.
 */
class DistBuildTraceRandom {

  private final Random random;

  public DistBuildTraceRandom(Random random) {
    this.random = random;
  }

  private String randomMinionId() {
    return "slave" + (random.nextLong() & Long.MAX_VALUE);
  }

  private int randomBuildDuration() {
    return random.nextInt(60_000);
  }

  private long randomTaskDuration(long buildDuration) {
    if (random.nextInt(30) == 0) {
      // generate some very short events
      return random.nextInt(3);
    }

    return random.nextInt(Math.toIntExact(buildDuration));
  }

  private String randomRuleName() {
    return "//rule" + (random.nextLong() & Long.MAX_VALUE);
  }

  private List<RuleTrace> randomMinionHistory(long start, long finish) {
    ArrayList<RuleTrace> result = new ArrayList<>();

    int lineCount = random.nextInt(24);

    for (int i = 0; i < lineCount; ++i) {
      generateRandomLine(start, finish, result);
    }

    result.sort(Comparator.comparingLong(e -> e.startEpochMillis));

    return result;
  }

  private void generateRandomLine(long start, long finish, List<RuleTrace> result) {
    long currentStart = start;
    for (; ; ) {
      currentStart += randomTaskDuration(finish - start);

      long end = currentStart + randomTaskDuration(finish - start);
      if (end > finish) {
        return;
      }

      result.add(new RuleTrace(randomRuleName(), currentStart, end));

      currentStart = end;
    }
  }

  private StampedeId randomStampedeId() {
    StampedeId stampedeId = new StampedeId();
    stampedeId.id = "stampede" + (random.nextLong() & Long.MAX_VALUE);
    return stampedeId;
  }

  private DistBuildTrace random() {
    long start = System.currentTimeMillis();
    long finish = start + randomBuildDuration();
    int minionCount = random.nextInt(5);
    HashMap<String, List<RuleTrace>> historyByMinionId = new HashMap<>();
    for (int i = 0; i < minionCount; ++i) {
      historyByMinionId.put(randomMinionId(), randomMinionHistory(start, finish));
    }
    return new DistBuildTrace(randomStampedeId(), historyByMinionId);
  }

  public static DistBuildTrace random(Random random) {
    return new DistBuildTraceRandom(random).random();
  }
}
