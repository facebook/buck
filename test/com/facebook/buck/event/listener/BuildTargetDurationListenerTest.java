/*
 * Copyright 2018-present Facebook, Inc.
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

import static com.facebook.buck.event.listener.BuildTargetDurationListener.computeCriticalPathsUsingGraphTraversal;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.findCriticalNode;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.rendersCriticalPathTraceFile;

import com.facebook.buck.event.listener.BuildTargetDurationListener.BuildRuleCriticalPath;
import com.facebook.buck.event.listener.BuildTargetDurationListener.BuildRuleInfo;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.Maps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Uses following DAG to test computations in BuildTargetDurationListener.
 *
 * <pre>{
 *                             +-+
 *                             |a|[12-20]
 *                             +++
 *                              |
 *                              |
 *                  +-----------+--------+
 *                  |                    |
 *                  |                    |
 *                  v                    v
 *                 +++                  +++
 *                 |b|[8-12]            |c|[9-11]
 *                 +++                  +++
 *                  |                    |
 *                  |                    |
 *          +-------+-----+ +------------+-------------+
 *          |             | |            |             |
 *          v             v v            v             v
 *         +++            +-+           +++           +++
 *         |d|[2-4]       |e|[6-8]      |f|8-9]       |g|[1-2]
 *         +++            +++           +++           +++
 *          |              |             |             |
 *  +-------+--+          ++-------+ +---+-----+       v
 *  |          |          |        | |         |      +++
 *  v          v          v        v v         v      |h|[0-1]
 * +++        +++        +++       +-+        +++     +-+
 * |i|[0-1]   |j|[0-2]   |k|[0-5]  |l|[0-6]   |m|[0-8]
 * +-+        +-+        +-+       +-+        +-+
 * }</pre>
 */
public class BuildTargetDurationListenerTest {

  private ConcurrentMap<String, BuildRuleInfo> buildRuleInfos;

  private static final String EXPECTED_JSON_OUTPUT =
      "[{\"target-name\":\"a\","
          + "\"target-duration\":8,"
          + "\"critical-path\":"
          + "[{\"rule-name\":\"l\",\"start\":0,\"finish\":6,\"duration\":6},"
          + "{\"rule-name\":\"e\",\"start\":6,\"finish\":8,\"duration\":2},"
          + "{\"rule-name\":\"b\",\"start\":8,\"finish\":12,\"duration\":4},"
          + "{\"rule-name\":\"a\",\"start\":12,\"finish\":20,\"duration\":8}],"
          + "\"critical-path-duration\":20},"
          + "{\"target-name\":\"c\","
          + "\"target-duration\":2,"
          + "\"critical-path\":"
          + "[{\"rule-name\":\"m\",\"start\":0,\"finish\":8,\"duration\":8},"
          + "{\"rule-name\":\"f\",\"start\":8,\"finish\":9,\"duration\":1},"
          + "{\"rule-name\":\"c\",\"start\":9,\"finish\":11,\"duration\":2}],"
          + "\"critical-path-duration\":11}]";

  private static final String EXPECTED_TRACE =
      "[{\"cat\":\"buck\",\"name\":\"process_name\",\"ph\":\"M\",\"pid\":0,\"tid\":0,"
          + "\"ts\":0,\"tts\":0,\"args\":{\"name\":\"Critical Path\"}},"
          + "{\"cat\":\"buck\",\"name\":\"l\",\"ph\":\"B\",\"pid\":0,\"tid\":0,\"ts\":0,\"tts\":0,"
          + "\"args\":{\"critical_blocking_rule\":\"None\",\"length_of_critical_path\":\"6ms\"}},"
          + "{\"cat\":\"buck\",\"name\":\"l\",\"ph\":\"E\",\"pid\":0,\"tid\":0,\"ts\":6000,"
          + "\"tts\":0,\"args\":{}},{\"cat\":\"buck\",\"name\":\"e\",\"ph\":\"B\",\"pid\":0,"
          + "\"tid\":0,\"ts\":6000,\"tts\":0,\"args\":{\"critical_blocking_rule\":\"l\","
          + "\"length_of_critical_path\":\"8ms\"}},{\"cat\":\"buck\",\"name\":\"e\",\"ph\":\"E\","
          + "\"pid\":0,\"tid\":0,\"ts\":8000,\"tts\":0,\"args\":{}},{\"cat\":\"buck\","
          + "\"name\":\"b\",\"ph\":\"B\",\"pid\":0,\"tid\":0,\"ts\":8000,\"tts\":0,"
          + "\"args\":{\"critical_blocking_rule\":\"e\",\"length_of_critical_path\":\"12ms\"}},"
          + "{\"cat\":\"buck\",\"name\":\"b\",\"ph\":\"E\",\"pid\":0,\"tid\":0,\"ts\":12000,"
          + "\"tts\":0,\"args\":{}},{\"cat\":\"buck\",\"name\":\"a\",\"ph\":\"B\",\"pid\":0,"
          + "\"tid\":0,\"ts\":12000,\"tts\":0,\"args\":{\"critical_blocking_rule\":\"b\","
          + "\"length_of_critical_path\":\"20ms\"}},{\"cat\":\"buck\",\"name\":\"a\",\"ph\":\"E\","
          + "\"pid\":0,\"tid\":0,\"ts\":20000,\"tts\":0,\"args\":{}}]";

  @Before
  public void setUp() {
    buildRuleInfos = Maps.newConcurrentMap();
    BuildRuleInfo a = new BuildRuleInfo("a", 12, 20);
    a.updateDuration(8);
    BuildRuleInfo b = new BuildRuleInfo("b", 8, 12);
    b.updateDuration(4);
    BuildRuleInfo c = new BuildRuleInfo("c", 9, 11);
    c.updateDuration(2);
    BuildRuleInfo d = new BuildRuleInfo("d", 2, 4);
    d.updateDuration(2);
    BuildRuleInfo e = new BuildRuleInfo("e", 6, 8);
    e.updateDuration(2);
    BuildRuleInfo f = new BuildRuleInfo("f", 8, 9);
    f.updateDuration(1);
    BuildRuleInfo g = new BuildRuleInfo("g", 1, 2);
    g.updateDuration(1);
    BuildRuleInfo h = new BuildRuleInfo("h", 0, 1);
    h.updateDuration(1);
    BuildRuleInfo i = new BuildRuleInfo("i", 0, 1);
    i.updateDuration(1);
    BuildRuleInfo j = new BuildRuleInfo("j", 0, 2);
    j.updateDuration(2);
    BuildRuleInfo k = new BuildRuleInfo("k", 0, 5);
    k.updateDuration(5);
    BuildRuleInfo l = new BuildRuleInfo("l", 0, 6);
    l.updateDuration(6);
    BuildRuleInfo m = new BuildRuleInfo("m", 0, 8);
    m.updateDuration(8);

    a.addDependency("b");
    a.addDependency("c");
    b.addDependency("d");
    b.addDependency("e");
    b.addDependent("a");
    c.addDependency("e");
    c.addDependency("f");
    c.addDependency("g");
    c.addDependent("a");
    d.addDependency("i");
    d.addDependency("j");
    d.addDependent("b");
    e.addDependency("k");
    e.addDependency("l");
    e.addDependent("b");
    e.addDependent("c");
    f.addDependency("l");
    f.addDependency("m");
    f.addDependent("c");
    g.addDependency("h");
    g.addDependent("c");
    i.addDependent("d");
    j.addDependent("d");
    k.addDependent("e");
    l.addDependent("e");
    l.addDependent("f");
    m.addDependent("f");
    h.addDependent("g");

    buildRuleInfos.put("a", a);
    buildRuleInfos.put("b", b);
    buildRuleInfos.put("c", c);
    buildRuleInfos.put("d", d);
    buildRuleInfos.put("e", e);
    buildRuleInfos.put("f", f);
    buildRuleInfos.put("g", g);
    buildRuleInfos.put("h", h);
    buildRuleInfos.put("i", i);
    buildRuleInfos.put("j", j);
    buildRuleInfos.put("k", k);
    buildRuleInfos.put("l", l);
    buildRuleInfos.put("m", m);
  }

  private void checkCriticalPath(Optional<BuildRuleInfo> critical) {
    Assert.assertEquals("a", critical.get().ruleName);
    // Critical path
    Assert.assertEquals(20, buildRuleInfos.get("a").longestDependencyChainMillis);
    Assert.assertEquals(
        "b", buildRuleInfos.get("a").previousRuleInLongestDependencyChain.get().ruleName);
    Assert.assertEquals(12, buildRuleInfos.get("b").longestDependencyChainMillis);
    Assert.assertEquals(
        "e", buildRuleInfos.get("b").previousRuleInLongestDependencyChain.get().ruleName);
    Assert.assertEquals(8, buildRuleInfos.get("e").longestDependencyChainMillis);
    Assert.assertEquals(
        "l", buildRuleInfos.get("e").previousRuleInLongestDependencyChain.get().ruleName);
    Assert.assertEquals(6, buildRuleInfos.get("l").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), buildRuleInfos.get("l").previousRuleInLongestDependencyChain);

    Assert.assertEquals(11, buildRuleInfos.get("c").longestDependencyChainMillis);
    Assert.assertEquals(
        "f", buildRuleInfos.get("c").previousRuleInLongestDependencyChain.get().ruleName);
    Assert.assertEquals(9, buildRuleInfos.get("f").longestDependencyChainMillis);
    Assert.assertEquals(
        "m", buildRuleInfos.get("f").previousRuleInLongestDependencyChain.get().ruleName);
    Assert.assertEquals(8, buildRuleInfos.get("m").longestDependencyChainMillis);
    Assert.assertEquals(
        Optional.empty(), buildRuleInfos.get("m").previousRuleInLongestDependencyChain);
  }

  @Test
  public void testTraversalGraphInterface() throws IOException {
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    checkCriticalPath(findCriticalNode(buildRuleInfos.values()));
  }

  @Test
  public void testOutputTrace() throws IOException {
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    BuildRuleInfo[] targets =
        new BuildRuleInfo[] {buildRuleInfos.get("a"), buildRuleInfos.get("c")};

    List<BuildRuleCriticalPath> criticalPaths =
        Arrays.stream(targets)
            .map(BuildTargetDurationListener::constructBuildRuleCriticalPath)
            .collect(Collectors.toList());
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      ObjectMappers.WRITER.writeValue(bos, criticalPaths);
      Assert.assertEquals(EXPECTED_JSON_OUTPUT, bos.toString());
    }
  }

  @Test
  public void testChromeTrace() throws IOException {
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      rendersCriticalPathTraceFile(buildRuleInfos, bos);
      Assert.assertEquals(EXPECTED_TRACE, bos.toString());
    }
  }
}
