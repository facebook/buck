/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;

public class RuleMap {

  /** Utility class: do not instantiate. */
  private RuleMap() {}

  public static ActionGraph createGraphFromBuildRules(BuildRuleResolver params) {
    Iterable<BuildRule> rules = params.getBuildRules();
    return createGraphFromBuildRules(rules);
  }

  public static ActionGraph createGraphFromSingleRule(BuildRule buildRule) {
    return createGraphFromBuildRules(ImmutableList.of(buildRule));
  }

  private static ActionGraph createGraphFromBuildRules(Iterable<BuildRule> rules) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();
    for (BuildRule rule : rules) {
      graph.addNode(rule);
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }
    return new ActionGraph(graph);
  }

}
