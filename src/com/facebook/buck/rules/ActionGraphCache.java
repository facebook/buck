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

import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.IncrementalActionGraphMode;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ActionGraphPerfStatEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * Class that transforms {@link TargetGraph} to {@link ActionGraph}. It also holds a cache for the
 * last ActionGraph it generated.
 */
public class ActionGraphCache {
  private static final Logger LOG = Logger.get(ActionGraphCache.class);

  private Cache<TargetGraph, ActionGraphAndResolver> previousActionGraphs;
  private ActionGraphNodeCache nodeCache;

  public ActionGraphCache(int maxEntries, int maxNodeCacheEntries) {
    previousActionGraphs = CacheBuilder.newBuilder().maximumSize(maxEntries).build();
    nodeCache = new ActionGraphNodeCache(maxNodeCacheEntries);
  }

  /** Create an ActionGraph, using options extracted from a BuckConfig. */
  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      BuckConfig buckConfig,
      RuleKeyConfiguration ruleKeyConfiguration,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    return getActionGraph(
        eventBus,
        buckConfig.isActionGraphCheckingEnabled(),
        buckConfig.isSkipActionGraphCache(),
        targetGraph,
        cellProvider,
        ruleKeyConfiguration,
        buckConfig.getActionGraphParallelizationMode(),
        Optional.empty(),
        buckConfig.getShouldInstrumentActionGraph(),
        buckConfig.getIncrementalActionGraphMode(),
        poolSupplier);
  }

  /** Create an ActionGraph, using options extracted from a BuckConfig. */
  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      BuckConfig buckConfig,
      RuleKeyConfiguration ruleKeyConfiguration,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    return getActionGraph(
        eventBus,
        buckConfig.isActionGraphCheckingEnabled(),
        buckConfig.isSkipActionGraphCache(),
        targetGraph,
        cellProvider,
        ruleKeyConfiguration,
        buckConfig.getActionGraphParallelizationMode(),
        ruleKeyLogger,
        buckConfig.getShouldInstrumentActionGraph(),
        buckConfig.getIncrementalActionGraphMode(),
        poolSupplier);
  }

  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      boolean checkActionGraphs,
      boolean skipActionGraphCache,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      RuleKeyConfiguration ruleKeyConfiguration,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    return getActionGraph(
        eventBus,
        checkActionGraphs,
        skipActionGraphCache,
        targetGraph,
        cellProvider,
        ruleKeyConfiguration,
        parallelizationMode,
        Optional.empty(),
        shouldInstrumentGraphBuilding,
        incrementalActionGraphMode,
        poolSupplier);
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
   * @param poolSupplier the thread poolSupplier for parallel action graph construction
   * @return a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      boolean checkActionGraphs,
      boolean skipActionGraphCache,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      RuleKeyConfiguration ruleKeyConfiguration,
      ActionGraphParallelizationMode parallelizationMode,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);
    ActionGraphAndResolver out;
    ActionGraphEvent.Finished finished = ActionGraphEvent.finished(started);
    try {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(ruleKeyConfiguration);
      ActionGraphAndResolver cachedActionGraph = previousActionGraphs.getIfPresent(targetGraph);
      if (cachedActionGraph != null) {
        eventBus.post(ActionGraphEvent.Cache.hit());
        LOG.info("ActionGraph cache hit.");
        if (checkActionGraphs) {
          compareActionGraphs(
              eventBus,
              cachedActionGraph,
              targetGraph,
              cellProvider,
              fieldLoader,
              parallelizationMode,
              ruleKeyLogger,
              shouldInstrumentGraphBuilding,
              incrementalActionGraphMode,
              poolSupplier);
        }
        out = cachedActionGraph;
      } else {
        eventBus.post(ActionGraphEvent.Cache.miss(previousActionGraphs.size() == 0));
        LOG.debug("Computing TargetGraph HashCode...");
        if (previousActionGraphs.size() == 0) {
          LOG.info("ActionGraph cache miss. Cache was empty.");
          eventBus.post(ActionGraphEvent.Cache.missWithEmptyCache());
        } else {
          // If we get here, that means the cache is not empty, but the target graph wasn't
          // in the cache.
          LOG.info("ActionGraph cache miss against " + previousActionGraphs.size() + " entries.");
          eventBus.post(ActionGraphEvent.Cache.missWithTargetGraphDifference());
        }
        Pair<TargetGraph, ActionGraphAndResolver> freshActionGraph =
            new Pair<TargetGraph, ActionGraphAndResolver>(
                targetGraph,
                createActionGraph(
                    eventBus,
                    new DefaultTargetNodeToBuildRuleTransformer(),
                    targetGraph,
                    cellProvider,
                    parallelizationMode,
                    shouldInstrumentGraphBuilding,
                    incrementalActionGraphMode,
                    poolSupplier));
        out = freshActionGraph.getSecond();
        if (!skipActionGraphCache) {
          LOG.info("ActionGraph cache assignment. skipActionGraphCache? %s", skipActionGraphCache);
          previousActionGraphs.put(freshActionGraph.getFirst(), freshActionGraph.getSecond());
        }
      }
      finished = ActionGraphEvent.finished(started, out.getActionGraph().getSize());
      return out;
    } finally {
      eventBus.post(finished);
    }
  }

  /**
   * * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a {@link DefaultTargetNodeToBuildRuleTransformer}.
   *
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @param parallelizationMode
   * @return a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getFreshActionGraph(
      BuckEventBus eventBus,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    return getFreshActionGraph(
        eventBus,
        transformer,
        targetGraph,
        cellProvider,
        parallelizationMode,
        shouldInstrumentGraphBuilding,
        incrementalActionGraphMode,
        poolSupplier);
  }

  /**
   * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a custom {@link TargetNodeToBuildRuleTransformer}.
   *
   * @param eventBus The {@link BuckEventBus} to post the events of the processing.
   * @param transformer Custom {@link TargetNodeToBuildRuleTransformer} that the transformation will
   *     be based on.
   * @param targetGraph The target graph that the action graph will be based on.
   * @param parallelizationMode
   * @return It returns a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getFreshActionGraph(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    ActionGraphAndResolver actionGraph =
        createActionGraph(
            eventBus,
            transformer,
            targetGraph,
            cellProvider,
            parallelizationMode,
            shouldInstrumentGraphBuilding,
            incrementalActionGraphMode,
            poolSupplier);

    eventBus.post(ActionGraphEvent.finished(started, actionGraph.getActionGraph().getSize()));
    return actionGraph;
  }

  private ActionGraphAndResolver createActionGraph(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {

    if (incrementalActionGraphMode == IncrementalActionGraphMode.EXPERIMENT) {
      incrementalActionGraphMode =
          RandomizedTrial.getGroupStable(
              "incremental_action_graph", IncrementalActionGraphMode.class);
      Preconditions.checkState(incrementalActionGraphMode != IncrementalActionGraphMode.EXPERIMENT);
      eventBus.post(
          new ExperimentEvent(
              "incremental_action_graph", incrementalActionGraphMode.toString(), "", null, null));
    }

    switch (parallelizationMode) {
      case EXPERIMENT:
        parallelizationMode =
            RandomizedTrial.getGroupStable(
                "action_graph_parallelization", ActionGraphParallelizationMode.class);
        eventBus.post(
            new ExperimentEvent(
                "action_graph_parallelization", parallelizationMode.toString(), "", null, null));
        break;
      case EXPERIMENT_UNSTABLE:
        parallelizationMode =
            RandomizedTrial.getGroup(
                "action_graph_parallelization",
                eventBus.getBuildId().toString(),
                ActionGraphParallelizationMode.class);
        eventBus.post(
            new ExperimentEvent(
                "action_graph_parallelization_unstable",
                parallelizationMode.toString(),
                "",
                null,
                null));
        break;
      case ENABLED:
      case DISABLED:
        break;
    }
    switch (parallelizationMode) {
      case ENABLED:
        return createActionGraphInParallel(
            transformer, targetGraph, cellProvider, incrementalActionGraphMode, poolSupplier.get());
      case DISABLED:
        return createActionGraphSerially(
            eventBus,
            transformer,
            targetGraph,
            cellProvider,
            shouldInstrumentGraphBuilding,
            incrementalActionGraphMode);
      case EXPERIMENT_UNSTABLE:
      case EXPERIMENT:
        throw new AssertionError(
            "EXPERIMENT* values should have been resolved to ENABLED or DISABLED.");
    }
    throw new AssertionError("Unexpected parallelization mode value: " + parallelizationMode);
  }

  private ActionGraphAndResolver createActionGraphInParallel(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      IncrementalActionGraphMode incrementalActionGraphMode,
      ForkJoinPool pool) {
    BuildRuleResolver resolver =
        new MultiThreadedBuildRuleResolver(pool, targetGraph, transformer, cellProvider);
    HashMap<BuildTarget, CompletableFuture<BuildRule>> futures = new HashMap<>();

    if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
      nodeCache.prepareForTargetGraphWalk(targetGraph, resolver);
    }
    try {
      // Don't descend into a node's children if we're loading that node from the cache, as the
      // entire subgraph will be fetched at once.
      Predicate<TargetNode<?, ?>> shouldExploreChildren =
          node ->
              incrementalActionGraphMode == IncrementalActionGraphMode.DISABLED
                  || !nodeCache.containsKey(node);

      LOG.debug("start target graph walk");
      new AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException>(targetGraph) {
        @Override
        public void visit(TargetNode<?, ?> node) {
          // If we're loading this node from cache, we don't need to wait on our children, as the
          // entire subgraph will be loaded from cache.
          CompletableFuture<BuildRule>[] depFutures =
              (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED
                      && nodeCache.containsKey(node))
                  ? RichStream.of().<CompletableFuture<BuildRule>>toArray(CompletableFuture[]::new)
                  : targetGraph
                      .getOutgoingNodesFor(node)
                      .stream()
                      .map(dep -> Preconditions.checkNotNull(futures.get(dep.getBuildTarget())))
                      .<CompletableFuture<BuildRule>>toArray(CompletableFuture[]::new);
          futures.put(
              node.getBuildTarget(),
              CompletableFuture.allOf(depFutures)
                  .thenApplyAsync(
                      ignored -> requireRule(resolver, node, incrementalActionGraphMode), pool));
        }
      }.traverse(shouldExploreChildren);

      // Wait for completion. The results are ignored as we only care about the rules populated in
      // the
      // resolver, which is a superset of the rules generated directly from target nodes.
      try {
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[futures.size()]))
            .join();
      } catch (CompletionException e) {
        Throwables.throwIfUnchecked(e.getCause());
        throw new IllegalStateException("unexpected checked exception", e);
      }
      LOG.debug("end target graph walk");
    } finally {
      if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
        nodeCache.finishTargetGraphWalk();
      }
    }

    return ActionGraphAndResolver.builder()
        .setActionGraph(new ActionGraph(resolver.getBuildRules()))
        .setResolver(resolver)
        .build();
  }

  private ActionGraphAndResolver createActionGraphSerially(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode) {
    // TODO: Reduce duplication between the serial and parallel creation methods.
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(targetGraph, transformer, cellProvider);

    if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
      nodeCache.prepareForTargetGraphWalk(targetGraph, resolver);
    }
    try {
      // Don't descend into a node's children if we're loading that node from the cache, as the
      // entire
      // subgraph will be fetched at once.
      Predicate<TargetNode<?, ?>> shouldExploreChildren =
          node ->
              incrementalActionGraphMode == IncrementalActionGraphMode.DISABLED
                  || !nodeCache.containsKey(node);

      LOG.debug("start target graph walk");
      new AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException>(targetGraph) {
        @Override
        public void visit(TargetNode<?, ?> node) {
          if (shouldInstrumentGraphBuilding) {
            Clock clock = new DefaultClock();
            try (Scope ignored =
                ActionGraphPerfStatEvent.start(
                    clock,
                    eventBus,
                    () -> Iterables.size(resolver.getBuildRules()),
                    () ->
                        StreamSupport.stream(resolver.getBuildRules().spliterator(), true)
                            .filter(
                                rule ->
                                    rule instanceof NoopBuildRule
                                        || rule instanceof NoopBuildRuleWithDeclaredAndExtraDeps)
                            .count(),
                    node.getDescription().getClass().getName(),
                    node.getBuildTarget().getFullyQualifiedName())) {
              requireRule(resolver, node, incrementalActionGraphMode);
            }
          } else {
            requireRule(resolver, node, incrementalActionGraphMode);
          }
        }
      }.traverse(shouldExploreChildren);
      LOG.debug("end target graph walk");
    } finally {
      if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
        nodeCache.finishTargetGraphWalk();
      }
    }

    return ActionGraphAndResolver.builder()
        .setActionGraph(new ActionGraph(resolver.getBuildRules()))
        .setResolver(resolver)
        .build();
  }

  private BuildRule requireRule(
      BuildRuleResolver resolver,
      TargetNode<?, ?> targetNode,
      IncrementalActionGraphMode incrementalActionGraphMode) {
    if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
      return nodeCache.requireRule(targetNode);
    }
    return resolver.requireRule(targetNode.getBuildTarget());
  }

  private static Map<BuildRule, RuleKey> getRuleKeysFromBuildRules(
      Iterable<BuildRule> buildRules,
      BuildRuleResolver buildRuleResolver,
      RuleKeyFieldLoader fieldLoader,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(fieldLoader, pathResolver, ruleFinder, ruleKeyLogger);

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
   * @param fieldLoader
   * @param parallelizationMode What mode to use when processing the action graphs
   * @param ruleKeyLogger The logger to use (if any) when computing the new action graph
   * @param poolSupplier The thread poolSupplier to use for parallel action graph construction
   */
  private void compareActionGraphs(
      BuckEventBus eventBus,
      ActionGraphAndResolver lastActionGraphAndResolver,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      RuleKeyFieldLoader fieldLoader,
      ActionGraphParallelizationMode parallelizationMode,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier) {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("ActionGraphCacheCheck"))) {
      // We check that the lastActionGraph is not null because it's possible we had a
      // invalidateCache() between the scheduling and the execution of this task.
      LOG.info("ActionGraph integrity check spawned.");
      Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
          new Pair<TargetGraph, ActionGraphAndResolver>(
              targetGraph,
              createActionGraph(
                  eventBus,
                  new DefaultTargetNodeToBuildRuleTransformer(),
                  targetGraph,
                  cellProvider,
                  parallelizationMode,
                  shouldInstrumentGraphBuilding,
                  incrementalActionGraphMode,
                  poolSupplier));

      Map<BuildRule, RuleKey> lastActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              lastActionGraphAndResolver.getActionGraph().getNodes(),
              lastActionGraphAndResolver.getResolver(),
              fieldLoader,
              Optional.empty() /* Only log once, and only for the new graph */);
      Map<BuildRule, RuleKey> newActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              newActionGraph.getSecond().getActionGraph().getNodes(),
              newActionGraph.getSecond().getResolver(),
              fieldLoader,
              ruleKeyLogger);

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

  private void invalidateCache() {
    previousActionGraphs.invalidateAll();
  }
}
