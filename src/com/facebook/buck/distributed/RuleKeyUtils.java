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

package com.facebook.buck.distributed;

import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/** * Common rule key calculation helpers, used by both Stampede client and workers */
public class RuleKeyUtils {
  private static final Logger LOG = Logger.get(RuleKeyUtils.class);

  /**
   * Calculates default rule keys for nodes in given graph
   *
   * @return Map of rules to their keys
   */
  public static ListenableFuture<List<Pair<BuildRule, RuleKey>>> calculateDefaultRuleKeys(
      ActionGraphBuilder actionGraphBuilder,
      ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator,
      BuckEventBus eventBus,
      Iterable<BuildTarget> topLevelTargets) {
    Set<BuildRule> allRulesInGraph = findAllRulesInGraph(topLevelTargets, actionGraphBuilder);

    List<ListenableFuture<Pair<BuildRule, RuleKey>>> ruleKeys =
        new ArrayList<>(allRulesInGraph.size());
    for (BuildRule rule : allRulesInGraph) {
      ruleKeys.add(
          Futures.transform(
              ruleKeyCalculator.calculate(eventBus, rule),
              ruleKey -> {
                LOG.debug("Rule key calculation: [%s] [%s]", rule.getFullyQualifiedName(), ruleKey);
                return new Pair<>(rule, ruleKey);
              },
              MoreExecutors.directExecutor()));
    }

    return Futures.allAsList(ruleKeys);
  }

  private static Set<BuildRule> findAllRulesInGraph(
      Iterable<BuildTarget> topLevelTargets, BuildRuleResolver resolver) {
    LOG.info("Finding all rules in graph...");

    DefaultRuleDepsCache ruleDepsCache = new DefaultRuleDepsCache(resolver);
    Set<BuildRule> allRules = new HashSet<>();

    Queue<BuildRule> rulesToProcess =
        RichStream.from(topLevelTargets)
            .map(resolver::getRule)
            .collect(Collectors.toCollection(LinkedList::new));

    while (!rulesToProcess.isEmpty()) {
      BuildRule buildRule = rulesToProcess.remove();

      if (allRules.contains(buildRule)) {
        continue;
      }
      allRules.add(buildRule);

      rulesToProcess.addAll(ruleDepsCache.get(buildRule));
    }

    LOG.info("Found %s rules in graph.", allRules.size());

    return allRules;
  }
}
