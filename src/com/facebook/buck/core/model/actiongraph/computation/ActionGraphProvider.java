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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class that transforms {@link TargetGraph} to {@link ActionGraph}. It also holds a cache for the
 * last ActionGraph it generated.
 */
public class ActionGraphProvider {
  private static final Logger LOG = Logger.get(ActionGraphProvider.class);

  private final BuckEventBus eventBus;
  private final ActionGraphFactory actionGraphFactory;
  private final ActionGraphCache actionGraphCache;
  private final RuleKeyConfiguration ruleKeyConfiguration;
  private final boolean checkActionGraphs;
  private final boolean skipActionGraphCache;
  private final IncrementalActionGraphMode incrementalActionGraphMode;

  public ActionGraphProvider(
      BuckEventBus eventBus,
      ActionGraphFactory actionGraphFactory,
      ActionGraphCache actionGraphCache,
      RuleKeyConfiguration ruleKeyConfiguration,
      boolean checkActionGraphs,
      boolean skipActionGraphCache,
      IncrementalActionGraphMode incrementalActionGraphMode) {
    this.eventBus = eventBus;
    this.actionGraphFactory = actionGraphFactory;
    this.actionGraphCache = actionGraphCache;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.checkActionGraphs = checkActionGraphs;
    this.skipActionGraphCache = skipActionGraphCache;
    this.incrementalActionGraphMode = incrementalActionGraphMode;
  }

  private ActionGraphProvider(
      BuckEventBus eventBus,
      ActionGraphFactory actionGraphFactory,
      ActionGraphCache actionGraphCache,
      RuleKeyConfiguration ruleKeyConfiguration,
      ActionGraphConfig actionGraphConfig) {
    this(
        eventBus,
        actionGraphFactory,
        actionGraphCache,
        ruleKeyConfiguration,
        actionGraphConfig.isActionGraphCheckingEnabled(),
        actionGraphConfig.isSkipActionGraphCache(),
        actionGraphConfig.getIncrementalActionGraphMode());
  }

  public ActionGraphProvider(
      BuckEventBus eventBus,
      ActionGraphFactory actionGraphFactory,
      ActionGraphCache actionGraphCache,
      RuleKeyConfiguration ruleKeyConfiguration,
      BuckConfig buckConfig) {
    this(
        eventBus,
        actionGraphFactory,
        actionGraphCache,
        ruleKeyConfiguration,
        buckConfig.getView(ActionGraphConfig.class));
  }

  /** Create an ActionGraph, using options extracted from a BuckConfig. */
  public ActionGraphAndBuilder getActionGraph(TargetGraph targetGraph) {
    return getActionGraph(
        new DefaultTargetNodeToBuildRuleTransformer(), targetGraph, Optional.empty());
  }

  /**
   * It returns an {@link ActionGraphAndBuilder}. If the {@code targetGraph} exists in the cache it
   * returns a cached version of the {@link ActionGraphAndBuilder}, else returns a new one and
   * updates the cache.
   *
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndBuilder}
   */
  public ActionGraphAndBuilder getActionGraph(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);
    ActionGraphAndBuilder out;
    ActionGraphEvent.Finished finished = ActionGraphEvent.finished(started);
    try {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(ruleKeyConfiguration);
      ActionGraphAndBuilder cachedActionGraph = actionGraphCache.getIfPresent(targetGraph);
      if (cachedActionGraph != null) {
        eventBus.post(ActionGraphEvent.Cache.hit());
        LOG.info("ActionGraph cache hit.");
        if (checkActionGraphs) {
          compareActionGraphs(
              cachedActionGraph, transformer, targetGraph, fieldLoader, ruleKeyLogger);
        }
        out = cachedActionGraph;
      } else {
        eventBus.post(ActionGraphEvent.Cache.miss(actionGraphCache.isEmpty()));
        LOG.debug("Computing TargetGraph HashCode...");
        if (actionGraphCache.isEmpty()) {
          LOG.info("ActionGraph cache miss. Cache was empty.");
          eventBus.post(ActionGraphEvent.Cache.missWithEmptyCache());
        } else {
          // If we get here, that means the cache is not empty, but the target graph wasn't
          // in the cache.
          LOG.info("ActionGraph cache miss against " + actionGraphCache.size() + " entries.");
          eventBus.post(ActionGraphEvent.Cache.missWithTargetGraphDifference());
        }
        out =
            createActionGraph(
                transformer,
                targetGraph,
                skipActionGraphCache
                    ? IncrementalActionGraphMode.DISABLED
                    : incrementalActionGraphMode);
        if (!skipActionGraphCache) {
          LOG.info("ActionGraph cache assignment.");
          actionGraphCache.put(targetGraph, out);
        }
      }
      finished = ActionGraphEvent.finished(started, out.getActionGraph().getSize());
      return out;
    } finally {
      eventBus.post(finished);
    }
  }

  /**
   * * It returns a new {@link ActionGraphAndBuilder} based on the targetGraph without checking the
   * cache. It uses a {@link DefaultTargetNodeToBuildRuleTransformer}.
   *
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndBuilder}
   */
  public ActionGraphAndBuilder getFreshActionGraph(TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    return getFreshActionGraph(transformer, targetGraph);
  }

  /**
   * It returns a new {@link ActionGraphAndBuilder} based on the targetGraph without checking the
   * cache. It uses a custom {@link TargetNodeToBuildRuleTransformer}.
   *
   * @param transformer Custom {@link TargetNodeToBuildRuleTransformer} that the transformation will
   *     be based on.
   * @param targetGraph The target graph that the action graph will be based on.
   * @return It returns a {@link ActionGraphAndBuilder}
   */
  public ActionGraphAndBuilder getFreshActionGraph(
      TargetNodeToBuildRuleTransformer transformer, TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    ActionGraphAndBuilder actionGraph =
        createActionGraph(transformer, targetGraph, IncrementalActionGraphMode.DISABLED);

    eventBus.post(ActionGraphEvent.finished(started, actionGraph.getActionGraph().getSize()));
    return actionGraph;
  }

  private ActionGraphAndBuilder createActionGraph(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      IncrementalActionGraphMode incrementalActionGraphMode) {

    return actionGraphFactory.createActionGraph(
        transformer,
        targetGraph,
        incrementalActionGraphMode,
        graphBuilder -> {
          // Any previously cached action graphs are no longer valid, as we may use build rules
          // from those graphs to construct a new graph incrementally, and update those build
          // rules to use a new BuildRuleResolver.
          actionGraphCache.invalidateCache();

          // Populate the new build rule graphBuilder with all of the usable rules from the last
          // build rule graphBuilder for incremental action graph generation.
          actionGraphCache.populateActionGraphBuilderWithCachedRules(
              eventBus, targetGraph, graphBuilder);
        });
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
   * @param lastActionGraphAndBuilder The cached version of the graph that gets compared.
   * @param targetGraph Used to generate the actionGraph that gets compared with lastActionGraph.
   * @param fieldLoader
   * @param ruleKeyLogger The logger to use (if any) when computing the new action graph
   */
  private void compareActionGraphs(
      ActionGraphAndBuilder lastActionGraphAndBuilder,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      RuleKeyFieldLoader fieldLoader,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, PerfEventId.of("ActionGraphCacheCheck"))) {
      // We check that the lastActionGraph is not null because it's possible we had a
      // invalidateCache() between the scheduling and the execution of this task.
      LOG.info("ActionGraph integrity check spawned.");
      ActionGraphAndBuilder newActionGraph =
          createActionGraph(transformer, targetGraph, IncrementalActionGraphMode.DISABLED);

      Map<BuildRule, RuleKey> lastActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              lastActionGraphAndBuilder.getActionGraph().getNodes(),
              lastActionGraphAndBuilder.getActionGraphBuilder(),
              fieldLoader,
              Optional.empty() /* Only log once, and only for the new graph */);
      Map<BuildRule, RuleKey> newActionGraphRuleKeys =
          getRuleKeysFromBuildRules(
              newActionGraph.getActionGraph().getNodes(),
              newActionGraph.getActionGraphBuilder(),
              fieldLoader,
              ruleKeyLogger);

      if (!lastActionGraphRuleKeys.equals(newActionGraphRuleKeys)) {
        actionGraphCache.invalidateCache();
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
}
