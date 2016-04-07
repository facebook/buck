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
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyBuilderFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A cache for the last ActionGraph buck created.
 */
public class ActionGraphCache {

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

  public ActionGraphCache() {
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
  }

  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      final TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    TargetGraphToActionGraph targetGraphToActionGraph =
        new TargetGraphToActionGraph(eventBus, transformer);

    Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
        new Pair<TargetGraph, ActionGraphAndResolver>(
            targetGraph, targetGraphToActionGraph.apply(targetGraph));

    if (lastActionGraph != null && lastActionGraph.getFirst().equals(targetGraph)) {
      cacheHitCounter.inc();

      if (!equalRuleKeysFromActionGraphs(lastActionGraph.getSecond(), newActionGraph.getSecond())) {
        actionGraphsMismatch.inc();
      }
    } else {
      cacheMissCounter.inc();
    }

    lastActionGraph = newActionGraph;
    return lastActionGraph.getSecond();
  }

  public static boolean equalRuleKeysFromActionGraphs(
      ActionGraphAndResolver graph1,
      ActionGraphAndResolver graph2) {
    Map<BuildRule, RuleKey> graph1RuleKeys = getRuleKeysFromBuildRules(
        graph1.getActionGraph().getNodes(),
        graph2.getResolver());
    Map<BuildRule, RuleKey> graph2RuleKeys = getRuleKeysFromBuildRules(
        graph2.getActionGraph().getNodes(),
        graph2.getResolver());

    return graph1RuleKeys.equals(graph2RuleKeys);
  }

  public static Map<BuildRule, RuleKey> getRuleKeysFromBuildRules(
      Iterable<BuildRule> buildRules,
      BuildRuleResolver buildRuleResolver
  ) {
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    ContentAgnosticRuleKeyBuilderFactory factory =
        new ContentAgnosticRuleKeyBuilderFactory(pathResolver);

    HashMap<BuildRule, RuleKey> ruleKeysMap = new HashMap<>();

    for (BuildRule rule : buildRules) {
      ruleKeysMap.put(rule, factory.build(rule));
    }

    return ruleKeysMap;
  }

  public ImmutableList<Counter> getCounters() {
    return ImmutableList.<Counter>of(
        cacheHitCounter,
        cacheMissCounter,
        actionGraphsMismatch);
  }
}
