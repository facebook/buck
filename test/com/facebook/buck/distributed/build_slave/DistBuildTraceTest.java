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
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class DistBuildTraceTest {

  @Test
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

    RuleTrace oneOffRule = new RuleTrace("z", 0, 5);

    List<MinionTrace> minionTraces =
        DistBuildTrace.guessMinionThreadAssignment(
            ImmutableMap.of("m1", inputRules, "m2", Lists.newArrayList(oneOffRule)));
    Assert.assertEquals(2, minionTraces.size());

    // We want to verify that we did not infer the number of threads to be more than the actual
    // number of threads, and that all rule traces come up exactly once in the minion trace.
    MinionTrace minionTrace1 = minionTraces.get(0);
    Assert.assertEquals(3, minionTrace1.threads.size());
    List<RuleTrace> outputRules = new ArrayList<>();
    minionTrace1.threads.forEach(thread -> outputRules.addAll(thread.ruleTraces));

    inputRules.sort(Comparator.comparingInt(RuleTrace::hashCode));
    outputRules.sort(Comparator.comparingInt(RuleTrace::hashCode));
    Assert.assertArrayEquals(
        inputRules.toArray(new RuleTrace[0]), outputRules.toArray(new RuleTrace[0]));

    MinionTrace minionTrace2 = minionTraces.get(1);
    Assert.assertEquals(1, minionTrace2.threads.size());
    MinionThread thread0 = minionTrace2.threads.get(0);
    Assert.assertEquals(1, thread0.ruleTraces.size());
    Assert.assertEquals(oneOffRule, thread0.ruleTraces.get(0));
  }

  @Test
  public void testCriticalPathComputation() {
    /* Graph:
     *                  a
     *                  |
     *             +----+------+
     *             |           |
     *             b           c(uncachable)
     *             |           |
     *         +---^----v------+----+
     *         |        |      |    |
     *         d        e(uc)  f    g(uc)
     *         |        |      |    |
     *       +-^---+  +-^---v--^-+  h
     *       |     |  |     |    |
     *       i(uc) j  k(uc) l    m
     */
    DistributableBuildGraph graph =
        DistributableBuildGraphTest.createGraph(
            ImmutableList.of(
                new Pair<>("a", "b"),
                new Pair<>("a", "c"),
                new Pair<>("b", "d"),
                new Pair<>("b", "e"),
                new Pair<>("c", "e"),
                new Pair<>("c", "f"),
                new Pair<>("c", "g"),
                new Pair<>("d", "i"),
                new Pair<>("d", "j"),
                new Pair<>("e", "k"),
                new Pair<>("e", "l"),
                new Pair<>("f", "l"),
                new Pair<>("f", "m"),
                new Pair<>("g", "h")),
            ImmutableSet.of("c", "e", "g", "i", "k"));

    /* Execution times:
     *                  a(12-20)
     *                  |
     *             +----+------+
     *             |           |
     *             b(8-12)     c(na)
     *             |           |
     *         +---^---------v-+-----+----------+
     *         |             |       |          |
     *         d(2-4)        e(na)   f(9-11)    g(na)
     *         |             |       |          |
     *       +-^---+       +-^----v--^-----+    h(0-1)
     *       |     |       |      |        |
     *       i(na) j(0-2)  k(na)  l(0-8)   m(0-2)
     */
    List<RuleTrace> inputRules = new ArrayList<>();
    inputRules.add(new RuleTrace("j", 0, 2));
    inputRules.add(new RuleTrace("l", 0, 8));
    inputRules.add(new RuleTrace("m", 0, 2));
    inputRules.add(new RuleTrace("h", 0, 1));
    inputRules.add(new RuleTrace("d", 2, 4));
    inputRules.add(new RuleTrace("f", 9, 11));
    inputRules.add(new RuleTrace("b", 8, 12));
    inputRules.add(new RuleTrace("a", 12, 20));
    // Randomly distribute the rules among the 2 minions.
    Collections.shuffle(inputRules);

    RuleTrace criticalRule =
        DistBuildTrace.computeCriticalPaths(
                ImmutableMap.of(
                    "m1", inputRules.subList(0, 3), "m2", inputRules.subList(3, inputRules.size())),
                graph)
            .get();
    Assert.assertEquals("a", criticalRule.ruleName);

    Map<String, RuleTrace> tracesByName =
        inputRules.stream().collect(Collectors.toMap(trace -> trace.ruleName, trace -> trace));

    Assert.assertEquals(20, tracesByName.get("a").longestDependencyChainMillis);
    Assert.assertEquals(
        "b", tracesByName.get("a").previousRuleInLongestDependencyChain.get().ruleName);

    Assert.assertEquals(12, tracesByName.get("b").longestDependencyChainMillis);
    Assert.assertEquals(
        "l", tracesByName.get("b").previousRuleInLongestDependencyChain.get().ruleName);

    Assert.assertEquals(4, tracesByName.get("d").longestDependencyChainMillis);
    Assert.assertEquals(
        "j", tracesByName.get("d").previousRuleInLongestDependencyChain.get().ruleName);

    Assert.assertEquals(10, tracesByName.get("f").longestDependencyChainMillis);
    Assert.assertEquals(
        "l", tracesByName.get("f").previousRuleInLongestDependencyChain.get().ruleName);

    Assert.assertEquals(1, tracesByName.get("h").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), tracesByName.get("h").previousRuleInLongestDependencyChain);

    Assert.assertEquals(2, tracesByName.get("j").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), tracesByName.get("j").previousRuleInLongestDependencyChain);

    Assert.assertEquals(8, tracesByName.get("l").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), tracesByName.get("l").previousRuleInLongestDependencyChain);

    Assert.assertEquals(2, tracesByName.get("m").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), tracesByName.get("m").previousRuleInLongestDependencyChain);
  }
}
