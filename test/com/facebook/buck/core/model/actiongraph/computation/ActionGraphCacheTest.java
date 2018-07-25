/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.core.model.actiongraph.computation;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.config.IncrementalActionGraphMode;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ActionGraphCacheTest {

  private static final boolean CHECK_GRAPHS = true;
  private static final boolean NOT_CHECK_GRAPHS = false;

  private TargetNode<?> nodeA;
  private TargetNode<?> nodeB;
  private TargetGraph targetGraph1;
  private TargetGraph targetGraph2;

  CloseableMemoizedSupplier<ForkJoinPool> fakePoolSupplier;

  private BuckEventBus eventBus;
  private BlockingQueue<BuckEvent> trackedEvents = new LinkedBlockingQueue<>();
  private final int keySeed = 0;

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Rule public TemporaryPaths tmpFilePath = new TemporaryPaths();

  @Before
  public void setUp() {
    // Creates the following target graph:
    //      A
    //     /
    //    B

    nodeB = createTargetNode("B");
    nodeA = createTargetNode("A", nodeB);
    targetGraph1 = TargetGraphFactory.newInstance(nodeA, nodeB);
    targetGraph2 = TargetGraphFactory.newInstance(nodeB);

    fakePoolSupplier =
        CloseableMemoizedSupplier.of(
            () -> {
              throw new IllegalStateException(
                  "should not use parallel executor for single threaded action graph construction in test");
            },
            ignored -> {});

    eventBus =
        BuckEventBusForTests.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)));

    trackedEvents.clear();
    eventBus.register(
        new Object() {
          @Subscribe
          public void actionGraphCacheEvent(ActionGraphEvent.Cache event) {
            trackedEvents.add(event);
          }

          @Subscribe
          public void actionGraphCacheEvent(ExperimentEvent event) {
            trackedEvents.add(event);
          }
        });
  }

  @Test
  public void hitOnCache() {
    ActionGraphCache cache = new ActionGraphCache(1);

    ActionGraphAndBuilder resultRun1 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS, /* skipActionGraphCache */
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.DISABLED,
            ImmutableMap.of(),
            fakePoolSupplier);
    // The 1st time you query the ActionGraph it's a cache miss.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 0);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 1);

    ActionGraphAndBuilder resultRun2 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS, /* skipActionGraphCache */
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.DISABLED,
            ImmutableMap.of(),
            fakePoolSupplier);
    // The 2nd time it should be a cache hit and the ActionGraphs should be exactly the same.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 1);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 1);

    // Check all the RuleKeys are the same between the 2 ActionGraphs.
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun1.getActionGraph().getNodes(), resultRun1.getActionGraphBuilder());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun2.getActionGraph().getNodes(), resultRun2.getActionGraphBuilder());

    assertThat(resultRun1RuleKeys, equalTo(resultRun2RuleKeys));
  }

  @Test
  public void hitOnMultiEntryCache() {
    ActionGraphCache cache = new ActionGraphCache(2);

    // List of (graph to run, (expected hit count, expected miss count))
    ArrayList<Pair<TargetGraph, Pair<Integer, Integer>>> runList = new ArrayList<>();

    // First run for graph 1 should be a miss.
    runList.add(new Pair<>(targetGraph1, new Pair<>(0, 1)));
    // First run for graph 2 should be a miss.
    runList.add(new Pair<>(targetGraph2, new Pair<>(0, 2)));
    // Second run for graph 2 should be a hit.
    runList.add(new Pair<>(targetGraph2, new Pair<>(1, 2)));
    // Second run for graph 1 should be a hit.
    runList.add(new Pair<>(targetGraph1, new Pair<>(2, 2)));
    // Third run for graph 2 should be a hit again.
    runList.add(new Pair<>(targetGraph2, new Pair<>(3, 2)));

    runAndCheckExpectedHitMissCount(cache, runList);
  }

  @Test
  public void testLruEvictionOrder() {
    ActionGraphCache cache = new ActionGraphCache(2);

    // List of (graph to run, (expected hit count, expected miss count))
    ArrayList<Pair<TargetGraph, Pair<Integer, Integer>>> runList = new ArrayList<>();

    // First run for graph 1 should be a miss.
    runList.add(new Pair<>(targetGraph1, new Pair<>(0, 1)));
    // First run for graph 2 should be a miss.
    runList.add(new Pair<>(targetGraph2, new Pair<>(0, 2)));
    // Run graph 1 again to make it the MRU.
    runList.add(new Pair<>(targetGraph1, new Pair<>(1, 2)));
    // Run empty graph to evict graph 2.
    runList.add(new Pair<>(TargetGraph.EMPTY, new Pair<>(1, 3)));
    // Another run with graph 2 should be a miss (it should have just been evicted)
    runList.add(new Pair<>(targetGraph2, new Pair<>(1, 4)));
    // Now cache order should be (by LRU): EMPTY, targetGraph2
    runList.add(new Pair<>(targetGraph1, new Pair<>(1, 5)));

    runAndCheckExpectedHitMissCount(cache, runList);
  }

  private void runAndCheckExpectedHitMissCount(
      ActionGraphCache cache, List<Pair<TargetGraph, Pair<Integer, Integer>>> runList) {
    for (Pair<TargetGraph, Pair<Integer, Integer>> run : runList) {
      cache.getActionGraph(
          eventBus,
          CHECK_GRAPHS, /* skipActionGraphCache */
          false,
          run.getFirst(),
          new TestCellBuilder().build().getCellProvider(),
          TestRuleKeyConfigurationFactory.create(),
          ActionGraphParallelizationMode.DISABLED,
          false,
          IncrementalActionGraphMode.DISABLED,
          ImmutableMap.of(),
          fakePoolSupplier);

      assertEquals(
          countEventsOf(ActionGraphEvent.Cache.Hit.class), (int) run.getSecond().getFirst());
      assertEquals(
          countEventsOf(ActionGraphEvent.Cache.Miss.class), (int) run.getSecond().getSecond());
    }
  }

  @Test
  public void missOnCache() {
    ActionGraphCache cache = new ActionGraphCache(1);
    ActionGraphAndBuilder resultRun1 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS, /* skipActionGraphCache */
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.DISABLED,
            ImmutableMap.of(),
            fakePoolSupplier);
    // Each time you call it for a different TargetGraph so all calls should be misses.
    assertEquals(0, countEventsOf(ActionGraphEvent.Cache.Hit.class));
    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.Miss.class));

    trackedEvents.clear();
    ActionGraphAndBuilder resultRun2 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS,
            /* skipActionGraphCache */ false,
            targetGraph1.getSubgraph(ImmutableSet.of(nodeB)),
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.DISABLED,
            ImmutableMap.of(),
            fakePoolSupplier);
    assertEquals(0, countEventsOf(ActionGraphEvent.Cache.Hit.class));
    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.Miss.class));
    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.MissWithTargetGraphDifference.class));

    trackedEvents.clear();
    ActionGraphAndBuilder resultRun3 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS, /* skipActionGraphCache */
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.DISABLED,
            ImmutableMap.of(),
            fakePoolSupplier);
    assertEquals(0, countEventsOf(ActionGraphEvent.Cache.Hit.class));
    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.Miss.class));

    // Run1 and Run2 should not match, but Run1 and Run3 should
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun1.getActionGraph().getNodes(), resultRun1.getActionGraphBuilder());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun2.getActionGraph().getNodes(), resultRun2.getActionGraphBuilder());
    Map<BuildRule, RuleKey> resultRun3RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun3.getActionGraph().getNodes(), resultRun3.getActionGraphBuilder());

    // Run2 is done in a subgraph and it should not have the same ActionGraph.
    assertThat(resultRun1RuleKeys, Matchers.not(equalTo(resultRun2RuleKeys)));
    // Run1 and Run3 should match.
    assertThat(resultRun1RuleKeys, equalTo(resultRun3RuleKeys));
  }

  // If this breaks it probably means the ActionGraphCache checking also breaks.
  @Test
  public void compareActionGraphsBasedOnRuleKeys() {
    ActionGraphCache actionGraphCache = new ActionGraphCache(1);
    ActionGraphAndBuilder resultRun1 =
        actionGraphCache.getFreshActionGraph(
            eventBus,
            new DefaultTargetNodeToBuildRuleTransformer(),
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            ActionGraphParallelizationMode.DISABLED,
            false,
            fakePoolSupplier);

    ActionGraphAndBuilder resultRun2 =
        actionGraphCache.getFreshActionGraph(
            eventBus,
            new DefaultTargetNodeToBuildRuleTransformer(),
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            ActionGraphParallelizationMode.DISABLED,
            false,
            fakePoolSupplier);

    // Check all the RuleKeys are the same between the 2 ActionGraphs.
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun1.getActionGraph().getNodes(), resultRun1.getActionGraphBuilder());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(
            resultRun2.getActionGraph().getNodes(), resultRun2.getActionGraphBuilder());

    assertThat(resultRun1RuleKeys, equalTo(resultRun2RuleKeys));
  }

  @Test
  public void actionGraphParallelizationStateIsLogged() {
    List<ExperimentEvent> experimentEvents;
    try (CloseableMemoizedSupplier<ForkJoinPool> poolSupplier =
        CloseableMemoizedSupplier.of(
            () -> MostExecutors.forkJoinPoolWithThreadLimit(1, 1), ForkJoinPool::shutdownNow)) {
      for (ActionGraphParallelizationMode mode :
          ImmutableSet.of(
              ActionGraphParallelizationMode.DISABLED, ActionGraphParallelizationMode.ENABLED)) {
        new ActionGraphCache(1)
            .getActionGraph(
                eventBus,
                NOT_CHECK_GRAPHS, /* skipActionGraphCache */
                false,
                targetGraph1,
                new TestCellBuilder().build().getCellProvider(),
                TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
                mode,
                false,
                IncrementalActionGraphMode.DISABLED,
                ImmutableMap.of(),
                poolSupplier);
        experimentEvents =
            RichStream.from(trackedEvents.stream())
                .filter(ExperimentEvent.class)
                .collect(Collectors.toList());
        assertThat(
            "No experiment event is logged if not in experiment mode", experimentEvents, empty());
      }

      trackedEvents.clear();
      new ActionGraphCache(1)
          .getActionGraph(
              eventBus,
              NOT_CHECK_GRAPHS, /* skipActionGraphCache */
              false,
              targetGraph1,
              new TestCellBuilder().build().getCellProvider(),
              TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
              ActionGraphParallelizationMode.EXPERIMENT,
              false,
              IncrementalActionGraphMode.DISABLED,
              ImmutableMap.of(),
              poolSupplier);
      experimentEvents =
          RichStream.from(trackedEvents.stream())
              .filter(ExperimentEvent.class)
              .collect(Collectors.toList());
      assertThat(
          "EXPERIMENT mode should log either enabled or disabled.",
          experimentEvents,
          contains(
              allOf(
                  hasProperty("tag", equalTo("action_graph_parallelization")),
                  hasProperty("variant", anyOf(equalTo("ENABLED"), equalTo("DISABLED"))))));

      trackedEvents.clear();
      new ActionGraphCache(1)
          .getActionGraph(
              eventBus,
              NOT_CHECK_GRAPHS, /* skipActionGraphCache */
              false,
              targetGraph1,
              new TestCellBuilder().build().getCellProvider(),
              TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
              ActionGraphParallelizationMode.EXPERIMENT_UNSTABLE,
              false,
              IncrementalActionGraphMode.DISABLED,
              ImmutableMap.of(),
              poolSupplier);
      experimentEvents =
          RichStream.from(trackedEvents.stream())
              .filter(ExperimentEvent.class)
              .collect(Collectors.toList());
      assertThat(
          "EXPERIMENT mode should log either enabled or disabled.",
          experimentEvents,
          contains(
              allOf(
                  hasProperty("tag", equalTo("action_graph_parallelization_unstable")),
                  hasProperty("variant", anyOf(equalTo("ENABLED"), equalTo("DISABLED"))))));
    }
  }

  @Test
  public void incrementalActionGraphStateIsLogged() {
    List<ExperimentEvent> experimentEvents;
    for (IncrementalActionGraphMode mode :
        ImmutableSet.of(IncrementalActionGraphMode.DISABLED, IncrementalActionGraphMode.ENABLED)) {
      new ActionGraphCache(1)
          .getActionGraph(
              eventBus,
              NOT_CHECK_GRAPHS, /* skipActionGraphCache */
              false,
              targetGraph1,
              new TestCellBuilder().build().getCellProvider(),
              TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
              ActionGraphParallelizationMode.DISABLED,
              false,
              mode,
              ImmutableMap.of(),
              fakePoolSupplier);
      experimentEvents =
          RichStream.from(trackedEvents.stream())
              .filter(ExperimentEvent.class)
              .collect(Collectors.toList());
      assertThat(
          "No experiment event is logged if not in experiment mode", experimentEvents, empty());
    }

    trackedEvents.clear();

    ImmutableMap.Builder<IncrementalActionGraphMode, Double> experimentGroups =
        ImmutableMap.builder();
    experimentGroups.put(IncrementalActionGraphMode.ENABLED, 0.5);
    experimentGroups.put(IncrementalActionGraphMode.DISABLED, 0.5);
    new ActionGraphCache(1)
        .getActionGraph(
            eventBus,
            NOT_CHECK_GRAPHS, /* skipActionGraphCache */
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.EXPERIMENT,
            experimentGroups.build(),
            fakePoolSupplier);
    experimentEvents =
        RichStream.from(trackedEvents.stream())
            .filter(ExperimentEvent.class)
            .collect(Collectors.toList());
    assertThat(
        "EXPERIMENT mode should log either enabled or disabled.",
        experimentEvents,
        contains(
            allOf(
                hasProperty("tag", equalTo("incremental_action_graph")),
                hasProperty("variant", anyOf(equalTo("ENABLED"), equalTo("DISABLED"))))));

    trackedEvents.clear();
  }

  @Test
  public void cachedSubgraphReturnedFromNodeCacheSerial() {
    runCachedSubgraphReturnedFromNodeCacheTest(
        ActionGraphParallelizationMode.DISABLED, fakePoolSupplier);
  }

  @Test
  public void cachedSubgraphReturnedFromNodeCacheParallel() {
    try (CloseableMemoizedSupplier<ForkJoinPool> poolSupplier =
        CloseableMemoizedSupplier.of(
            () -> MostExecutors.forkJoinPoolWithThreadLimit(1, 1), ForkJoinPool::shutdownNow)) {
      runCachedSubgraphReturnedFromNodeCacheTest(
          ActionGraphParallelizationMode.ENABLED, poolSupplier);
    }
  }

  private void runCachedSubgraphReturnedFromNodeCacheTest(
      ActionGraphParallelizationMode parallelizationMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    ActionGraphCache cache = new ActionGraphCache(1);

    TargetNode<?> originalNode3 = createCacheableTargetNode("C");
    TargetNode<?> originalNode2 = createCacheableTargetNode("B", originalNode3);
    TargetNode<?> originalNode1 = createCacheableTargetNode("A", originalNode2);
    targetGraph1 = TargetGraphFactory.newInstance(originalNode1, originalNode2, originalNode3);

    ActionGraphAndBuilder originalResult =
        cache.getActionGraph(
            eventBus,
            NOT_CHECK_GRAPHS,
            false,
            targetGraph1,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            parallelizationMode,
            false,
            IncrementalActionGraphMode.ENABLED,
            ImmutableMap.of(),
            poolSupplier);

    BuildRule originalBuildRule1 =
        originalResult.getActionGraphBuilder().getRule(originalNode1.getBuildTarget());
    BuildRule originalBuildRule2 =
        originalResult.getActionGraphBuilder().getRule(originalNode2.getBuildTarget());
    BuildRule originalBuildRule3 =
        originalResult.getActionGraphBuilder().getRule(originalNode3.getBuildTarget());

    TargetNode<?> newNode4 = createCacheableTargetNode("D");
    TargetNode<?> newNode3 = createCacheableTargetNode("C");
    TargetNode<?> newNode2 = createCacheableTargetNode("B", newNode3);
    TargetNode<?> newNode1 = createCacheableTargetNode("A", newNode2, newNode4);
    targetGraph2 = TargetGraphFactory.newInstance(newNode1, newNode2, newNode3, newNode4);

    ActionGraphAndBuilder newResult =
        cache.getActionGraph(
            eventBus,
            NOT_CHECK_GRAPHS,
            false,
            targetGraph2,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(keySeed),
            parallelizationMode,
            false,
            IncrementalActionGraphMode.ENABLED,
            ImmutableMap.of(),
            poolSupplier);

    assertNotSame(
        originalBuildRule1, newResult.getActionGraphBuilder().getRule(newNode1.getBuildTarget()));
    assertSame(
        originalBuildRule2, newResult.getActionGraphBuilder().getRule(newNode2.getBuildTarget()));
    assertSame(
        originalBuildRule3, newResult.getActionGraphBuilder().getRule(newNode3.getBuildTarget()));
  }

  private TargetNode<?> createCacheableTargetNode(String name, TargetNode<?>... deps) {
    return FakeTargetNodeBuilder.newBuilder(BuildTargetFactory.newInstance("//foo:" + name))
        .setDeps(deps)
        .setProducesCacheableSubgraph(true)
        .build();
  }

  private TargetNode<?> createTargetNode(String name, TargetNode<?>... deps) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:" + name);
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget);
    for (TargetNode<?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    return targetNodeBuilder.build();
  }

  private int countEventsOf(Class<? extends ActionGraphEvent> trackedClass) {
    int i = 0;
    for (BuckEvent event : trackedEvents) {
      if (trackedClass.isInstance(event)) {
        i++;
      }
    }
    return i;
  }

  private Map<BuildRule, RuleKey> getRuleKeysFromBuildRules(
      Iterable<BuildRule> buildRules, BuildRuleResolver buildRuleResolver) {
    RuleKeyFieldLoader ruleKeyFieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(
            ruleKeyFieldLoader, pathResolver, ruleFinder, Optional.empty());

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();

    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }
}
