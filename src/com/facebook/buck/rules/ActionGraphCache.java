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

import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyBuilderFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * Class that transforms {@link TargetGraph} to {@link ActionGraph}. It also holds a cache for the
 * last ActionGraph it generated.
 */
public class ActionGraphCache {
  private static final Logger LOG = Logger.get(ActionGraphCache.class);

  private static final String COUNTER_CATEGORY = "buck_action_graph_cache";
  private static final String CACHE_HIT_COUNTER_NAME = "cache_hit";
  private static final String CACHE_MISS_COUNTER_NAME = "cache_miss";
  private static final String NEW_AND_CACHED_ACTIONGRAPHS_MISMATCH_NAME =
      "new_and_cached_actiongraphs_mismatch";

  private final IntegerCounter cacheHitCounter;
  private final IntegerCounter cacheMissCounter;
  private final IntegerCounter actionGraphsMismatch;

  @Nullable
  private Pair<TargetGraph, ActionGraphAndResolver> lastActionGraph;
  // RuleKey checking is done in a separate thread so it doesn't slow down critical path by much.
  private ExecutorService checkExecutor;
  private AtomicBoolean checkAlreadyRunning;
  public ActionGraphCache() {
    // Setting corePoolSize to 0 kills the thread every time the checking task is finished.
    // Setting thread priority to minimum so it doesn't content with buck's main work.
    this(new ThreadPoolExecutor(
        /* corePoolSize */ 0,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(),
        /* threadFactory */ new MoreExecutors.NamedAndPriorityThreadFactory(
            "ActionGraphCache-RuleCheck",
            Thread.MIN_PRIORITY),
        /* handler */ new ThreadPoolExecutor.DiscardPolicy()));
  }

  public ActionGraphCache(ExecutorService checkExecutor) {
    this.cacheHitCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        CACHE_HIT_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.cacheMissCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        CACHE_MISS_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.actionGraphsMismatch = new IntegerCounter(
        COUNTER_CATEGORY,
        NEW_AND_CACHED_ACTIONGRAPHS_MISMATCH_NAME,
        ImmutableMap.<String, String>of());
    this.checkExecutor = checkExecutor;
    this.checkAlreadyRunning = new AtomicBoolean(false);
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
      final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);
    try {
      if (lastActionGraph != null && lastActionGraph.getFirst().equals(targetGraph)) {
        cacheHitCounter.inc();
        LOG.info("ActionGraph cache hit.");
        if (checkActionGraphs) {
          spawnThreadToCompareActionGraphs(eventBus, targetGraph);
        }
      } else {
        cacheMissCounter.inc();
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
    final BuildRuleResolver resolver = new BuildRuleResolver(targetGraph, transformer);

    final int numberOfNodes = targetGraph.getNodes().size();
    final AtomicInteger processedNodes = new AtomicInteger(0);

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

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
      BuildRuleResolver buildRuleResolver) {
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    ContentAgnosticRuleKeyBuilderFactory factory =
        new ContentAgnosticRuleKeyBuilderFactory(pathResolver);

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();
    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }

  /**
   * Compares the cached ActionGraph with a newly generated from the targetGraph. The comparison
   * is done by generating and comparing content agnostic RuleKeys. This takes time so we spawn it
   * to another thread. In case of mismatch, the mismatching BuildRules are printed.
   * @param eventBus Buck's event bus
   * @param targetGraph Used to generate the actionGraph that gets compared with lastActionGraph.
   */
  private void spawnThreadToCompareActionGraphs(
      final BuckEventBus eventBus,
      final TargetGraph targetGraph) {
    // If a check already runs on a previous command do not interrupt and skip the test of this one.
    if (!checkAlreadyRunning.compareAndSet(false, true)) {
      return;
    }

    checkExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
            eventBus,
            PerfEventId.of("ActionGraphCacheCheck"))) {
          // We check that the lastActionGraph is not null because it's possible we had a
          // invalidateCache() between the scheduling and the execution of this task.
          if (lastActionGraph == null) {
            return;
          }
          LOG.info("ActionGraph integrity check spawned.");
          Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
              new Pair<TargetGraph, ActionGraphAndResolver>(
                  targetGraph,
                  createActionGraph(
                      eventBus,
                      new DefaultTargetNodeToBuildRuleTransformer(),
                      targetGraph));

          Map<BuildRule, RuleKey> lastActionGraphRuleKeys = getRuleKeysFromBuildRules(
              lastActionGraph.getSecond().getActionGraph().getNodes(),
              lastActionGraph.getSecond().getResolver());
          Map<BuildRule, RuleKey> newActionGraphRuleKeys = getRuleKeysFromBuildRules(
              newActionGraph.getSecond().getActionGraph().getNodes(),
              newActionGraph.getSecond().getResolver());

          if (!lastActionGraphRuleKeys.equals(newActionGraphRuleKeys)) {
            actionGraphsMismatch.inc();
            invalidateCache();
            Set<BuildRule> misMatchedBuildRules =
                Maps.difference(lastActionGraphRuleKeys, newActionGraphRuleKeys)
                    .entriesDiffering()
                    .keySet();
            String mismatchInfo = "RuleKeys of cached and new ActionGraph don't match. Rules " +
                "that did not match:\n";
            for (BuildRule rule : misMatchedBuildRules) {
              mismatchInfo += rule.toString() + "\n";
            }
            LOG.error(mismatchInfo);
          }
        } finally {
          checkAlreadyRunning.set(false);
        }
      }
    });
  }

  public void invalidateBasedOn(WatchEvent<?> event) throws InterruptedException {
    if (!isFileContentModificationEvent(event)) {
      LOG.info("ActionGraph cache invalidation due to Watchman event %s.", event);
      invalidateCache();
    }
  }

  @Subscribe
  private static boolean isFileContentModificationEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_MODIFY;
  }

  private void invalidateCache() {
    lastActionGraph = null;
  }

  public ImmutableList<Counter> getCounters() {
    return ImmutableList.<Counter>of(
        cacheHitCounter,
        cacheMissCounter,
        actionGraphsMismatch);
  }

  @VisibleForTesting
  boolean isEmpty() {
    return lastActionGraph == null;
  }
}
