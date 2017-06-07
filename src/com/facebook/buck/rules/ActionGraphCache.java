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
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.WatchmanOverflowEvent;
import com.facebook.buck.util.WatchmanPathEvent;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Class that transforms {@link TargetGraph} to {@link ActionGraph}. It also holds a cache for the
 * last ActionGraph it generated.
 */
public class ActionGraphCache {
  private static final Logger LOG = Logger.get(ActionGraphCache.class);

  @Nullable private Pair<TargetGraph, ActionGraphAndResolver> lastActionGraph;

  @Nullable private HashCode lastTargetGraphHash;

  private BroadcastEventListener broadcastEventListener;

  public ActionGraphCache(BroadcastEventListener broadcastEventListener) {
    this.broadcastEventListener = broadcastEventListener;
  }

  /**
   * It returns an {@link ActionGraphAndResolver}. If the {@code targetGraph} exists in the cache it
   * returns a cached version of the {@link ActionGraphAndResolver}, else returns a new one and
   * updates the cache.
   *
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param skipActionGraphCache if true, do not invalidate the {@link ActionGraph} cached in
   *     memory. Instead, create a new {@link ActionGraph} for this request, which should be
   *     garbage-collected at the end of the request.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getActionGraph(
      final BuckEventBus eventBus,
      final boolean checkActionGraphs,
      final boolean skipActionGraphCache,
      final TargetGraph targetGraph,
      int keySeed) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);
    ActionGraphAndResolver out;
    try {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(keySeed);
      if (lastActionGraph != null && lastActionGraph.getFirst().equals(targetGraph)) {
        eventBus.post(ActionGraphEvent.Cache.hit());
        LOG.info("ActionGraph cache hit.");
        if (checkActionGraphs) {
          compareActionGraphs(eventBus, lastActionGraph.getSecond(), targetGraph, fieldLoader);
        }
        out = lastActionGraph.getSecond();
      } else {
        eventBus.post(ActionGraphEvent.Cache.miss(lastActionGraph == null));
        LOG.debug("Computing TargetGraph HashCode...");
        HashCode targetGraphHash = getTargetGraphHash(targetGraph);
        if (lastActionGraph == null) {
          LOG.info("ActionGraph cache miss. Cache was empty.");
        } else if (Objects.equals(lastTargetGraphHash, targetGraphHash)) {
          LOG.info("ActionGraph cache miss. TargetGraphs mismatched but hashes are the same.");
          eventBus.post(ActionGraphEvent.Cache.missWithTargetGraphHashMatch());
        } else {
          LOG.info("ActionGraph cache miss. TargetGraphs mismatched.");
        }
        lastTargetGraphHash = targetGraphHash;
        Pair<TargetGraph, ActionGraphAndResolver> freshActionGraph =
            new Pair<TargetGraph, ActionGraphAndResolver>(
                targetGraph,
                createActionGraph(
                    eventBus, new DefaultTargetNodeToBuildRuleTransformer(), targetGraph));
        out = freshActionGraph.getSecond();
        if (!skipActionGraphCache) {
          LOG.info("ActionGraph cache assignment. skipActionGraphCache? %s", skipActionGraphCache);
          lastActionGraph = freshActionGraph;
        }
      }
    } finally {
      eventBus.post(ActionGraphEvent.finished(started));
    }
    return out;
  }

  /**
   * * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a {@link DefaultTargetNodeToBuildRuleTransformer}.
   *
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public static ActionGraphAndResolver getFreshActionGraph(
      final BuckEventBus eventBus, final TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    return getFreshActionGraph(eventBus, transformer, targetGraph);
  }

  /**
   * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a custom {@link TargetNodeToBuildRuleTransformer}.
   *
   * @param eventBus The {@link BuckEventBus} to post the events of the processing.
   * @param transformer Custom {@link TargetNodeToBuildRuleTransformer} that the transformation will
   *     be based on.
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

    AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException>(targetGraph) {

          @Override
          public void visit(TargetNode<?, ?> node) {
            try {
              resolver.requireRule(node.getBuildTarget());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }
          }
        };
    bottomUpTraversal.traverse();

    return ActionGraphAndResolver.builder()
        .setActionGraph(new ActionGraph(resolver.getBuildRules()))
        .setResolver(resolver)
        .build();
  }

  private static HashCode getTargetGraphHash(TargetGraph targetGraph) {
    Hasher hasher = Hashing.sha1().newHasher();
    ImmutableSet<TargetNode<?, ?>> nodes = targetGraph.getNodes();
    for (TargetNode<?, ?> targetNode : ImmutableSortedSet.copyOf(nodes)) {
      hasher.putBytes(targetNode.getRawInputsHashCode().asBytes());
    }
    return hasher.hash();
  }

  private static Map<BuildRule, RuleKey> getRuleKeysFromBuildRules(
      Iterable<BuildRule> buildRules,
      BuildRuleResolver buildRuleResolver,
      RuleKeyFieldLoader fieldLoader) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(fieldLoader, pathResolver, ruleFinder);

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();
    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }

  /**
   * Compares the cached ActionGraph with a newly generated from the targetGraph. The comparison is
   * done by generating and comparing content agnostic RuleKeys. In case of mismatch, the
   * mismatching BuildRules are printed and the building process is stopped.
   *
   * @param eventBus Buck's event bus.
   * @param lastActionGraphAndResolver The cached version of the graph that gets compared.
   * @param targetGraph Used to generate the actionGraph that gets compared with lastActionGraph.
   */
  private void compareActionGraphs(
      final BuckEventBus eventBus,
      final ActionGraphAndResolver lastActionGraphAndResolver,
      final TargetGraph targetGraph,
      final RuleKeyFieldLoader fieldLoader) {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("ActionGraphCacheCheck"))) {
      // We check that the lastActionGraph is not null because it's possible we had a
      // invalidateCache() between the scheduling and the execution of this task.
      LOG.info("ActionGraph integrity check spawned.");
      Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
          new Pair<TargetGraph, ActionGraphAndResolver>(
              targetGraph,
              createActionGraph(
                  eventBus, new DefaultTargetNodeToBuildRuleTransformer(), targetGraph));

      Map<BuildRule, RuleKey> lastActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              lastActionGraphAndResolver.getActionGraph().getNodes(),
              lastActionGraphAndResolver.getResolver(),
              fieldLoader);
      Map<BuildRule, RuleKey> newActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              newActionGraph.getSecond().getActionGraph().getNodes(),
              newActionGraph.getSecond().getResolver(),
              fieldLoader);

      if (!lastActionGraphRuleKeys.equals(newActionGraphRuleKeys)) {
        invalidateCache();
        String mismatchInfo = "RuleKeys of cached and new ActionGraph don't match:\n";
        MapDifference<BuildRule, RuleKey> mismatchedRules =
            Maps.difference(lastActionGraphRuleKeys, newActionGraphRuleKeys);
        mismatchInfo +=
            "Number of nodes in common/differing: "
                + mismatchedRules.entriesInCommon().size()
                + "/"
                + mismatchedRules.entriesDiffering().size()
                + "\n"
                + "Entries only in the cached ActionGraph: "
                + mismatchedRules.entriesOnlyOnLeft().size()
                + "Entries only in the newly created ActionGraph: "
                + mismatchedRules.entriesOnlyOnRight().size()
                + "The rules that did not match:\n";
        mismatchInfo += mismatchedRules.entriesDiffering().keySet().toString();
        LOG.error(mismatchInfo);
        throw new RuntimeException(mismatchInfo);
      }
    }
  }

  @Subscribe
  public void invalidateBasedOn(WatchmanPathEvent event) {
    // We invalidate in every case except a modify event.
    if (event.getKind() == WatchmanPathEvent.Kind.MODIFY) {
      return;
    }
    if (!isCacheEmpty()) {
      LOG.info("ActionGraphCache invalidation due to Watchman event %s.", event);
    }
    invalidateCache();
    switch (event.getKind()) {
      case CREATE:
        broadcastEventListener.broadcast(
            WatchmanStatusEvent.fileCreation(event.getPath().toString()));
        return;
      case DELETE:
        broadcastEventListener.broadcast(
            WatchmanStatusEvent.fileDeletion(event.getPath().toString()));
        return;
      case MODIFY:
        throw new IllegalStateException("Should have handled MODIFY event earlier.");
    }
    throw new IllegalStateException("Unhandled case: " + event.getKind());
  }

  @Subscribe
  public void invalidateBasedOn(WatchmanOverflowEvent event) {
    if (!isCacheEmpty()) {
      LOG.info("ActionGraphCache invalidation due to Watchman event %s.", event);
    }
    invalidateCache();
    broadcastEventListener.broadcast(WatchmanStatusEvent.overflow(event.getReason()));
  }

  private void invalidateCache() {
    lastActionGraph = null;
    lastTargetGraphHash = null;
  }

  @VisibleForTesting
  boolean isCacheEmpty() {
    return lastActionGraph == null;
  }
}
