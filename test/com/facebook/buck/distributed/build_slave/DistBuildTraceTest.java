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

import com.facebook.buck.distributed.build_slave.DistBuildTrace.MinionTrace;
import com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class DistBuildTraceTest {

  @Test
  @Ignore // TODO(shivanker): Make this test pass.
  public void testRulesAssignmentToThreads() {
    // These rules must fit into 3 threads.
    List<RuleTrace> inputRules = new ArrayList<>();
    inputRules.add(new RuleTrace("a", 0, 2));
    inputRules.add(new RuleTrace("b", 0, 3));
    inputRules.add(new RuleTrace("c", 0, 4));
    inputRules.add(new RuleTrace("d", 3, 7));
    inputRules.add(new RuleTrace("e", 4, 8));
    inputRules.add(new RuleTrace("f", 2, 10));
    inputRules.add(new RuleTrace("g", 7, 14));
    inputRules.add(new RuleTrace("h", 8, 10));
    inputRules.add(new RuleTrace("i", 10, 12));
    inputRules.add(new RuleTrace("j", 10, 14));
    inputRules.add(new RuleTrace("k", 14, 19));
    inputRules.add(new RuleTrace("l", 14, 17));

    DistBuildTrace trace =
        new DistBuildTrace(new StampedeId().setId("boo"), ImmutableMap.of("m1", inputRules));
    Assert.assertEquals(1, trace.minions.size());

    // We want to verify that we did not infer the number of threads to be more than the actual
    // number of threads, and that all rule traces come up exactly once in the minion trace.
    MinionTrace minionTrace = trace.minions.get(0);
    Assert.assertEquals(3, minionTrace.threads.size());
    List<RuleTrace> outputRules = new ArrayList<>();
    minionTrace.threads.forEach(thread -> outputRules.addAll(thread.ruleTraces));

    inputRules.sort(Comparator.comparingInt(RuleTrace::hashCode));
    outputRules.sort(Comparator.comparingInt(RuleTrace::hashCode));
    Assert.assertArrayEquals(
        inputRules.toArray(new RuleTrace[0]), outputRules.toArray(new RuleTrace[0]));
  }

  // TODO(shivanker, msienkiewicz): Add test with multiple minions to check that rules are not
  // assigned to the wrong minion.
}
