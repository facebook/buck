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

import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyBuilderFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * Class that transforms {@link TargetGraph} to {@link ActionGraph}. It also holds a cache for the
 * last ActionGraph it generated.
 */
public class ActionGraphCache {
  private static final Logger LOG = Logger.get(ActionGraphCache.class);

  @Nullable
  private Pair<TargetGraph, ActionGraphAndResolver> lastActionGraph;
  private BroadcastEventListener broadcastEventListener;

  public ActionGraphCache(BroadcastEventListener broadcastEventListener) {
    this.broadcastEventListener = broadcastEventListener;
  }

  /**
   * It returns an {@link ActionGraphAndResolver}. If the {@code targetGraph} exists in the cache
   * it returns a cached version of the {@link ActionGraphAndResolver}, else returns a new one and
   * updates the cache.
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getActionGraph(
      final BuckEventBus eventBus,
      final boolean checkActionGraphs,
      final TargetGraph targetGraph,
      int keySeed) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);
    try {
      if (lastActionGraph != null && lastActionGraph.getFirst().equals(targetGraph)) {
        eventBus.post(ActionGraphEvent.Cache.hit());
        LOG.info("ActionGraph cache hit.");
        if (checkActionGraphs) {
          compareActionGraphs(eventBus, lastActionGraph.getSecond(), targetGraph, keySeed);
        }
      } else {
        eventBus.post(ActionGraphEvent.Cache.miss());
        if (lastActionGraph == null) {
          LOG.info("ActionGraph cache miss. Cache was empty.");
        } else {
          LOG.info("ActionGraph cache miss. TargetGraphs mismatched.");
        }
        lastActionGraph = new Pair<TargetGraph, ActionGraphAndResolver>(
            targetGraph,
            createActionGraph(
                eventBus,
                new DefaultTargetNodeToBuildRuleTransformer(),
                targetGraph));
      }
    } finally {
      eventBus.post(ActionGraphEvent.finished(started));
    }
    return lastActionGraph.getSecond();
  }

  /**
   * * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking
   * the cache. It uses a {@link DefaultTargetNodeToBuildRuleTransformer}.
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public static ActionGraphAndResolver getFreshActionGraph(
      final BuckEventBus eventBus,
      final TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    return getFreshActionGraph(eventBus, transformer, targetGraph);
  }

  /**
   * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a custom {@link TargetNodeToBuildRuleTransformer}.
   * @param eventBus The {@link BuckEventBus} to post the events of the processing.
   * @param transformer Custom {@link TargetNodeToBuildRuleTransformer} that the transformation will
   *                    be based on.
   * @param targetGraph The target graph that the action graph will be based on.
   * @return It returns a {@link ActionGraphAndResolver}
   */
  public static ActionGraphAndResolver getFreshActionGraph(
      final BuckEventBus eventBus,
      final TargetNodeToBuildRuleTransformer transformer,
      final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    ActionGraphAndResolver actionGraph = createActionGraph(eventBus, transformer, targetGraph);

    eventBus.post(ActionGraphEvent.finished(started));
    return actionGraph;
  }

  private static ActionGraphAndResolver createActionGraph(
      final BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph) {
    final BuildRuleResolver resolver = new BuildRuleResolver(targetGraph, transformer, eventBus);

    final int numberOfNodes = targetGraph.getNodes().size();
    final AtomicInteger processedNodes = new AtomicInteger(0);

    AbstractBottomUpTraversal<TargetNode<?>, RuntimeException> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            try {
              resolver.requireRule(node.getBuildTarget());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }
            eventBus.post(ActionGraphEvent.processed(
                processedNodes.incrementAndGet(),
                numberOfNodes));
          }
        };
    bottomUpTraversal.traverse();

    return ActionGraphAndResolver.builder()
        .setActionGraph(new ActionGraph(resolver.getBuildRules()))
        .setResolver(resolver)
        .build();
  }

  private static Map<BuildRule, RuleKey> getRuleKeysFromBuildRules(
      Iterable<BuildRule> buildRules,
      BuildRuleResolver buildRuleResolver,
      int keySeed) {
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    ContentAgnosticRuleKeyBuilderFactory factory =
        new ContentAgnosticRuleKeyBuilderFactory(keySeed, pathResolver);

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();
    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }

  /**
   * Compares the cached ActionGraph with a newly generated from the targetGraph. The comparison
   * is done by generating and comparing content agnostic RuleKeys. In case of mismatch, the
   * mismatching BuildRules are printed and the building process is stopped.
   * @param eventBus Buck's event bus.
   * @param lastActionGraphAndResolver The cached version of the graph that gets compared.
   * @param targetGraph Used to generate the actionGraph that gets compared with lastActionGraph.
   */
  private void compareActionGraphs(
      final BuckEventBus eventBus,
      final ActionGraphAndResolver lastActionGraphAndResolver,
      final TargetGraph targetGraph,
      final int keySeed) {
    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("ActionGraphCacheCheck"))) {
      // We check that the lastActionGraph is not null because it's possible we had a
      // invalidateCache() between the scheduling and the execution of this task.
      LOG.info("ActionGraph integrity check spawned.");
      Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
          new Pair<TargetGraph, ActionGraphAndResolver>(
              targetGraph,
              createActionGraph(
                  eventBus,
                  new DefaultTargetNodeToBuildRuleTransformer(),
                  targetGraph));

      Map<BuildRule, RuleKey> lastActionGraphRuleKeys = getRuleKeysFromBuildRules(
          lastActionGraphAndResolver.getActionGraph().getNodes(),
          lastActionGraphAndResolver.getResolver(),
          keySeed);
      Map<BuildRule, RuleKey> newActionGraphRuleKeys = getRuleKeysFromBuildRules(
          newActionGraph.getSecond().getActionGraph().getNodes(),
          newActionGraph.getSecond().getResolver(),
          keySeed);

      if (!lastActionGraphRuleKeys.equals(newActionGraphRuleKeys)) {
        invalidateCache();
        String mismatchInfo = "RuleKeys of cached and new ActionGraph don't match:\n";
        MapDifference<BuildRule, RuleKey> mismatchedRules =
            Maps.difference(lastActionGraphRuleKeys, newActionGraphRuleKeys);
        mismatchInfo +=
            "Number of nodes in common/differing: " + mismatchedRules.entriesInCommon().size() +
                "/" + mismatchedRules.entriesDiffering().size() + "\n" +
                "Entries only in the cached ActionGraph: " +
                mismatchedRules.entriesOnlyOnLeft().size() +
                "Entries only in the newly created ActionGraph: " +
                mismatchedRules.entriesOnlyOnRight().size() +
                "The rules that did not match:\n";
        mismatchInfo += mismatchedRules.entriesDiffering().keySet().toString();
        LOG.error(mismatchInfo);
        throw new RuntimeException(mismatchInfo);
      }
    }
  }

  @Subscribe
  public void invalidateBasedOn(WatchEvent<?> event) {
    // We invalidate in every case except a modify event.
    if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) {
      LOG.info("ActionGraphCache invalidation due to Watchman event %s.", event);
      invalidateCache();

      if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
        broadcastEventListener.broadcast(WatchmanStatusEvent.overflow((String) event.context()));
      } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
        broadcastEventListener.broadcast(WatchmanStatusEvent.fileCreation());
      } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
        broadcastEventListener.broadcast(WatchmanStatusEvent.fileDeletion());
      }
    }
  }

  private void invalidateCache() {
    lastActionGraph = null;
  }

  @VisibleForTesting
  boolean isEmpty() {
    return lastActionGraph == null;
  }
}
