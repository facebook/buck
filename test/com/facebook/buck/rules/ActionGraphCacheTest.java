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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.WatchmanOverflowEvent;
import com.facebook.buck.util.WatchmanPathEvent;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ActionGraphCacheTest {

  private static final boolean CHECK_GRAPHS = true;
  private static final boolean NOT_CHECK_GRAPHS = false;

  private TargetNode<?, ?> nodeA;
  private TargetNode<?, ?> nodeB;
  private TargetGraph targetGraph;
  private BuckEventBus eventBus;
  private BroadcastEventListener broadcastEventListener;
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
    targetGraph = TargetGraphFactory.newInstance(nodeA, nodeB);

    eventBus =
        BuckEventBusFactory.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)));
    broadcastEventListener = new BroadcastEventListener();
    broadcastEventListener.addEventBus(eventBus);

    eventBus.register(
        new Object() {
          @Subscribe
          public void actionGraphCacheEvent(ActionGraphEvent.Cache event) {
            trackedEvents.add(event);
          }
        });
  }

  @Test
  public void hitOnCache() throws InterruptedException {
    ActionGraphCache cache = new ActionGraphCache(broadcastEventListener);

    ActionGraphAndResolver resultRun1 =
        cache.getActionGraph(
            eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    // The 1st time you query the ActionGraph it's a cache miss.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 0);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 1);

    ActionGraphAndResolver resultRun2 =
        cache.getActionGraph(
            eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    // The 2nd time it should be a cache hit and the ActionGraphs should be exactly the same.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 1);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 1);

    // Check all the RuleKeys are the same between the 2 ActionGraphs.
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(resultRun1.getActionGraph().getNodes(), resultRun1.getResolver());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(resultRun2.getActionGraph().getNodes(), resultRun2.getResolver());

    assertThat(resultRun1RuleKeys, Matchers.equalTo(resultRun2RuleKeys));
  }

  @Test
  public void missOnCache() {
    ActionGraphCache cache = new ActionGraphCache(broadcastEventListener);
    ActionGraphAndResolver resultRun1 =
        cache.getActionGraph(
            eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    // Each time you call it for a different TargetGraph so all calls should be misses.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 0);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 1);

    ActionGraphAndResolver resultRun2 =
        cache.getActionGraph(
            eventBus,
            CHECK_GRAPHS,
            /* skipActionGraphCache */ false,
            targetGraph.getSubgraph(ImmutableSet.of(nodeB)),
            keySeed);

    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 0);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 2);

    ActionGraphAndResolver resultRun3 =
        cache.getActionGraph(
            eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 0);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 3);

    // Run1 and Run2 should not match, but Run1 and Run3 should
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(resultRun1.getActionGraph().getNodes(), resultRun1.getResolver());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(resultRun2.getActionGraph().getNodes(), resultRun2.getResolver());
    Map<BuildRule, RuleKey> resultRun3RuleKeys =
        getRuleKeysFromBuildRules(resultRun3.getActionGraph().getNodes(), resultRun3.getResolver());

    // Run2 is done in a subgraph and it should not have the same ActionGraph.
    assertThat(resultRun1RuleKeys, Matchers.not(Matchers.equalTo(resultRun2RuleKeys)));
    // Run1 and Run3 should match.
    assertThat(resultRun1RuleKeys, Matchers.equalTo(resultRun3RuleKeys));
  }

  @Test
  public void missWithTargetGraphHashMatch() {
    ActionGraphCache cache = new ActionGraphCache(broadcastEventListener);
    cache.getActionGraph(
        eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.Miss.class));

    cache.getActionGraph(
        eventBus,
        CHECK_GRAPHS,
        /* skipActionGraphCache */ false,
        TargetGraphFactory.newInstance(nodeA, createTargetNode("B")),
        keySeed);

    assertEquals(1, countEventsOf(ActionGraphEvent.Cache.MissWithTargetGraphHashMatch.class));
    assertEquals(2, countEventsOf(ActionGraphEvent.Cache.Miss.class));
  }

  // If this breaks it probably means the ActionGraphCache checking also breaks.
  @Test
  public void compareActionGraphsBasedOnRuleKeys() {
    ActionGraphAndResolver resultRun1 =
        ActionGraphCache.getFreshActionGraph(
            eventBus, new DefaultTargetNodeToBuildRuleTransformer(), targetGraph);

    ActionGraphAndResolver resultRun2 =
        ActionGraphCache.getFreshActionGraph(
            eventBus, new DefaultTargetNodeToBuildRuleTransformer(), targetGraph);

    // Check all the RuleKeys are the same between the 2 ActionGraphs.
    Map<BuildRule, RuleKey> resultRun1RuleKeys =
        getRuleKeysFromBuildRules(resultRun1.getActionGraph().getNodes(), resultRun1.getResolver());
    Map<BuildRule, RuleKey> resultRun2RuleKeys =
        getRuleKeysFromBuildRules(resultRun2.getActionGraph().getNodes(), resultRun2.getResolver());

    assertThat(resultRun1RuleKeys, Matchers.equalTo(resultRun2RuleKeys));
  }

  @Test
  public void cacheInvalidationBasedOnEvents() throws IOException, InterruptedException {
    ActionGraphCache cache = new ActionGraphCache(broadcastEventListener);
    Path file = tmpFilePath.newFile("foo.txt");

    // Fill the cache. An overflow event should invalidate the cache.
    cache.getActionGraph(
        eventBus, NOT_CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertFalse(cache.isCacheEmpty());
    cache.invalidateBasedOn(WatchmanOverflowEvent.of(tmpFilePath.getRoot(), "testing"));
    assertTrue(cache.isCacheEmpty());

    // Fill the cache. Add a file and ActionGraphCache should be invalidated.
    cache.getActionGraph(
        eventBus, NOT_CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertFalse(cache.isCacheEmpty());
    cache.invalidateBasedOn(
        WatchmanPathEvent.of(tmpFilePath.getRoot(), WatchmanPathEvent.Kind.CREATE, file));
    assertTrue(cache.isCacheEmpty());

    //Re-fill cache. Remove a file and ActionGraphCache should be invalidated.
    cache.getActionGraph(
        eventBus, NOT_CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertFalse(cache.isCacheEmpty());
    cache.invalidateBasedOn(
        WatchmanPathEvent.of(tmpFilePath.getRoot(), WatchmanPathEvent.Kind.DELETE, file));
    assertTrue(cache.isCacheEmpty());

    // Re-fill cache. Modify contents of a file, ActionGraphCache should NOT be invalidated.
    cache.getActionGraph(
        eventBus, CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertFalse(cache.isCacheEmpty());
    cache.invalidateBasedOn(
        WatchmanPathEvent.of(tmpFilePath.getRoot(), WatchmanPathEvent.Kind.MODIFY, file));
    cache.getActionGraph(
        eventBus, NOT_CHECK_GRAPHS, /* skipActionGraphCache */ false, targetGraph, keySeed);
    assertFalse(cache.isCacheEmpty());

    // We should have 4 cache misses and 1 hit from when you request the same graph after a file
    // modification.
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Hit.class), 1);
    assertEquals(countEventsOf(ActionGraphEvent.Cache.Miss.class), 4);
  }

  private TargetNode<?, ?> createTargetNode(String name, TargetNode<?, ?>... deps) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:" + name);
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget);
    for (TargetNode<?, ?> dep : deps) {
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
    RuleKeyFieldLoader ruleKeyFieldLoader = new RuleKeyFieldLoader(0);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(ruleKeyFieldLoader, pathResolver, ruleFinder);

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();

    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }
}
