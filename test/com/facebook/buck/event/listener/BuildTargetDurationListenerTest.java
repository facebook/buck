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
import static com.facebook.buck.event.listener.BuildTargetDurationListener.constructBuildRuleCriticalPaths;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.constructBuildTargetResults;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.constructCriticalPaths;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.criticalPath;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.findCriticalNodes;
import static com.facebook.buck.event.listener.BuildTargetDurationListener.rendersCriticalPathsTraceFile;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.listener.BuildTargetDurationListener.BuildRuleInfo;
import com.facebook.buck.event.listener.BuildTargetDurationListener.BuildRuleInfo.Chain;
import com.facebook.buck.event.listener.BuildTargetDurationListener.BuildRuleInfoSelectedChain;
import com.facebook.buck.event.listener.BuildTargetDurationListener.CriticalPathEntry;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.MinMaxPriorityQueue;
import com.google.common.collect.Sets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Uses following DAG to test computations in BuildTargetDurationListener. Simple:
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
 *
 * VeryConnected:
 *
 * <pre>
 *                        +-+
 *                        |b|[20-23]
 *      +-+               +++
 *      |a|[25-30]         |
 *      +++          +-----+-----+
 *       |           |           |
 *       v           v           v
 *      +++         +++         +++
 *      |c|[3-25]   |d|[3-18]   |e|[12-20]
 *      +++         +++         +++
 *       |           |           |
 *       + +---------+-+         v
 *       | |           |        +++
 *       v v           v        |h|[0-12]
 *       +-+          +++       +-+
 *       |f|[0-3]     |g|[0-3]
 *       +-+          +-+
 * </pre>
 */
public class BuildTargetDurationListenerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ConcurrentMap<String, BuildRuleInfo> setUpSimpleBuildRuleInfos(int kk) {
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = Maps.newConcurrentMap();
    BuildRuleInfo a = new BuildRuleInfo("a", kk, 12, 20);
    a.updateDuration(8);
    a.addBuildRuleEventInterval(12000, 20000);
    BuildRuleInfo b = new BuildRuleInfo("b", kk, 8, 12);
    b.updateDuration(4);
    b.addBuildRuleEventInterval(8000, 12000);
    BuildRuleInfo c = new BuildRuleInfo("c", kk, 9, 11);
    c.updateDuration(2);
    c.addBuildRuleEventInterval(9000, 11000);
    BuildRuleInfo d = new BuildRuleInfo("d", kk, 2, 4);
    d.updateDuration(2);
    d.addBuildRuleEventInterval(2000, 4000);
    BuildRuleInfo e = new BuildRuleInfo("e", kk, 6, 8);
    e.updateDuration(2);
    e.addBuildRuleEventInterval(6000, 8000);
    BuildRuleInfo f = new BuildRuleInfo("f", kk, 8, 9);
    f.updateDuration(1);
    f.addBuildRuleEventInterval(8000, 9000);
    BuildRuleInfo g = new BuildRuleInfo("g", kk, 1, 2);
    g.updateDuration(1);
    g.addBuildRuleEventInterval(1000, 2000);
    BuildRuleInfo h = new BuildRuleInfo("h", kk, 0, 1);
    h.updateDuration(1);
    h.addBuildRuleEventInterval(0, 1000);
    BuildRuleInfo i = new BuildRuleInfo("i", kk, 0, 1);
    i.updateDuration(1);
    i.addBuildRuleEventInterval(0, 1000);
    BuildRuleInfo j = new BuildRuleInfo("j", kk, 0, 2);
    j.updateDuration(2);
    j.addBuildRuleEventInterval(0, 2000);
    BuildRuleInfo k = new BuildRuleInfo("k", kk, 0, 5);
    k.updateDuration(5);
    k.addBuildRuleEventInterval(0, 5000);
    BuildRuleInfo l = new BuildRuleInfo("l", kk, 0, 6);
    l.updateDuration(6);
    l.addBuildRuleEventInterval(0, 6000);
    BuildRuleInfo m = new BuildRuleInfo("m", kk, 0, 8);
    m.updateDuration(8);
    m.addBuildRuleEventInterval(0, 8000);

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

    return buildRuleInfos;
  }

  public ConcurrentMap<String, BuildRuleInfo> setUpVeryConnectedBuildRuleInfos(int kk) {
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = Maps.newConcurrentMap();
    BuildRuleInfo a = new BuildRuleInfo("a", kk, 25, 30);
    a.updateDuration(5);
    a.addBuildRuleEventInterval(25000, 30000);
    BuildRuleInfo b = new BuildRuleInfo("b", kk, 20, 23);
    b.updateDuration(3);
    b.addBuildRuleEventInterval(20000, 23000);
    BuildRuleInfo c = new BuildRuleInfo("c", kk, 3, 25);
    c.updateDuration(22);
    c.addBuildRuleEventInterval(3000, 25000);
    BuildRuleInfo d = new BuildRuleInfo("d", kk, 3, 18);
    d.updateDuration(15);
    d.addBuildRuleEventInterval(3000, 18000);
    BuildRuleInfo e = new BuildRuleInfo("e", kk, 12, 20);
    e.updateDuration(8);
    e.addBuildRuleEventInterval(12000, 20000);
    BuildRuleInfo f = new BuildRuleInfo("f", kk, 0, 3);
    f.updateDuration(3);
    f.addBuildRuleEventInterval(0, 3000);
    BuildRuleInfo g = new BuildRuleInfo("g", kk, 0, 3);
    g.updateDuration(3);
    g.addBuildRuleEventInterval(0, 3000);
    BuildRuleInfo h = new BuildRuleInfo("h", kk, 0, 12);
    h.updateDuration(12);
    h.addBuildRuleEventInterval(0, 12000);

    f.addDependent("c");
    f.addDependent("d");
    g.addDependent("d");
    h.addDependent("e");
    c.addDependent("a");
    d.addDependent("b");
    e.addDependent("b");

    a.addDependency("c");
    b.addDependency("d");
    b.addDependency("e");
    c.addDependency("f");
    d.addDependency("f");
    d.addDependency("g");
    e.addDependency("h");

    buildRuleInfos.put("a", a);
    buildRuleInfos.put("b", b);
    buildRuleInfos.put("c", c);
    buildRuleInfos.put("d", d);
    buildRuleInfos.put("e", e);
    buildRuleInfos.put("f", f);
    buildRuleInfos.put("g", g);
    buildRuleInfos.put("h", h);

    return buildRuleInfos;
  }

  private static void checkCriticalPath(
      List<String> expectedPrev,
      List<Long> expectedLongest,
      Deque<CriticalPathEntry> criticalPath) {
    Iterator<String> expectedPrevIterator = expectedPrev.iterator();
    Iterator<Long> expectedLongestIterator = expectedLongest.iterator();
    for (CriticalPathEntry criticalPathEntry : criticalPath) {
      Assert.assertTrue(expectedPrevIterator.hasNext());
      assertEquals(expectedPrevIterator.next(), criticalPathEntry.ruleName());
      Assert.assertTrue(expectedLongestIterator.hasNext());
      assertEquals(expectedLongestIterator.next().longValue(), criticalPathEntry.longest());
    }
  }

  private static ImmutableList<Deque<CriticalPathEntry>> criticalPathsFor(BuildRuleInfo target) {
    ImmutableList.Builder<Deque<CriticalPathEntry>> paths = ImmutableList.builder();
    Set<Chain> usedChains = Sets.newHashSet();
    for (BuildRuleInfo.Chain c : target.getChain()) {
      paths.add(criticalPath(c, target, usedChains));
    }
    return paths.build();
  }

  private static void checkCriticalPathsOfVeryConnected(BuildRuleInfo a, BuildRuleInfo b) {
    // Check path of a.
    List<Deque<CriticalPathEntry>> criticalPathsForA = criticalPathsFor(a);
    assertEquals(1, criticalPathsForA.size());
    checkCriticalPath(
        Arrays.asList("f", "c", "a"), Arrays.asList(3L, 25L, 30L), criticalPathsForA.get(0));

    // Check paths of b
    List<Deque<CriticalPathEntry>> criticalPathsForB = criticalPathsFor(b);
    assertEquals(3, criticalPathsForB.size());

    for (Deque<CriticalPathEntry> criticalPathForB : criticalPathsForB) {
      final String starting = criticalPathForB.getFirst().ruleName();
      switch (starting) {
        case "f":
          checkCriticalPath(
              Arrays.asList("f", "d", "b"), Arrays.asList(3L, 18L, 21L), criticalPathForB);
          break;
        case "g":
          checkCriticalPath(
              Arrays.asList("g", "d", "b"), Arrays.asList(3L, 18L, 21L), criticalPathForB);
          break;
        case "h":
          checkCriticalPath(
              Arrays.asList("h", "e", "b"), Arrays.asList(12L, 20L, 23L), criticalPathForB);
          break;
        default:
          Assert.fail(String.format("Unexpected starting for a critical path: %s", starting));
      }
    }

    // Check top 3 critical paths
    List<Deque<CriticalPathEntry>> topKPaths = constructCriticalPaths(Arrays.asList(a, b), 3);
    assertEquals(3, topKPaths.size());
    checkCriticalPath(Arrays.asList("f", "c", "a"), Arrays.asList(3L, 25L, 30L), topKPaths.get(0));
    checkCriticalPath(Arrays.asList("h", "e", "b"), Arrays.asList(12L, 20L, 23L), topKPaths.get(1));
    checkCriticalPath(Arrays.asList("f", "d", "b"), Arrays.asList(3L, 18L, 21L), topKPaths.get(2));
  }

  private static List<BuildRuleInfo> rootBuildRuleInfos(Map<String, BuildRuleInfo> buildRuleInfos) {
    return buildRuleInfos
        .values()
        .stream()
        .filter(buildRuleInfo -> buildRuleInfo.noDependent())
        .collect(Collectors.toList());
  }

  @Test
  public void testTraversalGraphInterface() throws IOException {
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpSimpleBuildRuleInfos(1);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    BuildRuleInfoSelectedChain selectedChain = findCriticalNodes(rootBuildRuleInfos, 1).poll();
    checkCriticalPath(
        Arrays.asList("l", "e", "b", "a"),
        Arrays.asList(6L, 8L, 12L, 20L),
        criticalPath(selectedChain.chain(), selectedChain.buildRuleInfo(), Sets.newHashSet()));
  }

  @Test
  public void testMultipleCriticalPaths() throws IOException {
    final int k = 3;
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpVeryConnectedBuildRuleInfos(k);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    checkCriticalPathsOfVeryConnected(buildRuleInfos.get("a"), buildRuleInfos.get("b"));
  }

  @Test
  public void testFindCriticalNodes() throws IOException {
    final int k = 3;
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpVeryConnectedBuildRuleInfos(k);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    // The most critical node should be "a"
    assertEquals(
        "a", findCriticalNodes(rootBuildRuleInfos, 1).poll().buildRuleInfo().getRuleName());

    // Check top k critical nodes
    MinMaxPriorityQueue<BuildRuleInfoSelectedChain> topKCriticalNodes =
        findCriticalNodes(rootBuildRuleInfos, k);
    Iterator<String> expectedRuleName = Arrays.asList("a", "b", "b").iterator();
    Iterator<Long> expectedLongest = Arrays.asList(30L, 23L, 21L).iterator();
    Iterator<String> expectedPrev = Arrays.asList("c", "e", "d").iterator();

    while (!topKCriticalNodes.isEmpty()) {
      BuildRuleInfoSelectedChain selectedChain = topKCriticalNodes.poll();
      Assert.assertTrue(expectedRuleName.hasNext());
      assertEquals(expectedRuleName.next(), selectedChain.buildRuleInfo().getRuleName());
      Assert.assertTrue(expectedLongest.hasNext());
      assertEquals(expectedLongest.next().longValue(), selectedChain.chain().getLongest());
      Assert.assertTrue(expectedPrev.hasNext());
      assertEquals(expectedPrev.next(), selectedChain.chain().getPrev().get().getRuleName());
    }
  }

  @Test
  public void testOutputTrace() throws IOException {
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpSimpleBuildRuleInfos(1);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
    BuildRuleInfo[] targets =
        new BuildRuleInfo[] {buildRuleInfos.get("a"), buildRuleInfos.get("c")};
    List<ImmutableBuildRuleCriticalPath> criticalPaths =
        constructBuildRuleCriticalPaths(constructCriticalPaths(Arrays.asList(targets), 2));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    String data = workspace.getFileContents("expected-output.json");

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      ObjectMappers.WRITER.writeValue(bos, criticalPaths);
      assertEquals(data.trim(), bos.toString());
    }
  }

  @Test
  public void testChromeTrace() throws IOException {
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpSimpleBuildRuleInfos(1);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    String data = workspace.getFileContents("expected-trace.json");

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      rendersCriticalPathsTraceFile(constructCriticalPaths(rootBuildRuleInfos, 1), bos);
      assertEquals(data.trim(), bos.toString());
    }
  }

  @Test
  public void testChromeTraceMultiplePaths() throws IOException {
    final int k = 3;
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpVeryConnectedBuildRuleInfos(k);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "veryconnected", tmp);
    workspace.setUp();
    String data = workspace.getFileContents("expected-trace.json");

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      rendersCriticalPathsTraceFile(constructCriticalPaths(rootBuildRuleInfos, k), bos);
      assertEquals(data.trim(), bos.toString());
    }
  }

  @Test
  public void testOutputTraceMultiplePaths() throws IOException {
    final int k = 3;
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpVeryConnectedBuildRuleInfos(k);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "veryconnected", tmp);
    workspace.setUp();
    String data = workspace.getFileContents("expected-output.json");

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      ObjectMappers.WRITER.writeValue(
          bos, constructBuildRuleCriticalPaths(constructCriticalPaths(rootBuildRuleInfos, k)));
      assertEquals(data.trim(), bos.toString());
    }
  }

  @Test
  public void testTargetsBuildTimes() throws IOException {
    final int k = 3;
    ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = setUpVeryConnectedBuildRuleInfos(k);
    List<BuildRuleInfo> rootBuildRuleInfos = rootBuildRuleInfos(buildRuleInfos);
    computeCriticalPathsUsingGraphTraversal(buildRuleInfos);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "veryconnected", tmp);
    workspace.setUp();
    String data = workspace.getFileContents("expected-target-results.json");

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      ObjectMappers.WRITER.writeValue(bos, constructBuildTargetResults(rootBuildRuleInfos));
      assertEquals(data.trim(), bos.toString());
    }
  }

  @Test
  public void testBuildStartFinishTimes() {
    final String target = "//:celik";
    BuildId buildId = new BuildId("19911501");
    BackgroundTaskManager bgTaskManager = new TestBackgroundTaskManager();
    BuildTargetDurationListener listener =
        new BuildTargetDurationListener(
            InvocationInfo.of(
                buildId,
                false,
                false,
                "test",
                Arrays.asList(target),
                Arrays.asList(),
                Paths.get(".")),
            new FakeProjectFilesystem(),
            1,
            bgTaskManager);
    BuildRuleDurationTracker tracker = new BuildRuleDurationTracker();
    BuildRule rule =
        new FakeTestRule(
            ImmutableSet.of(), BuildTargetFactory.newInstance(target), ImmutableSortedSet.of());
    BuildRuleEvent.Started begin = BuildRuleEvent.started(rule, tracker);
    begin.configure(0, 0, 0, 0, buildId);
    listener.buildRuleEventStarted(begin);
    BuildRuleEvent.Finished end =
        BuildRuleEvent.finished(
            begin,
            BuildRuleKeys.of(new RuleKey("19911501")),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            UploadToCacheResultType.UNCACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    end.configure(
        42, TimeUnit.MILLISECONDS.toNanos(42), TimeUnit.MILLISECONDS.toNanos(42), 0, buildId);
    listener.buildRuleEventFinished(end);
    BuildRuleInfo buildRuleInfo = listener.getBuildRuleInfos().get(target);
    assertEquals(42, buildRuleInfo.getWholeTargetDuration());
    listener.close();
    bgTaskManager.notify(Notification.COMMAND_END);
  }
}
