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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.google.common.collect.ImmutableSet;
import java.util.function.Predicate;

public class BuildRuleDependencyVisitors {
  private BuildRuleDependencyVisitors() {}

  /**
   * Given dependencies in inputs builds graph of transitive dependencies filtering them by
   * instanceOf T.
   *
   * @param inputs initial dependencies from which to build transitive closure
   * @param filter predicate to determine whether a node should be included
   * @param traverse predicate to determine whether this node should be traversed
   * @param <T> class to fitler on
   * @return filtered BuildRule DAG of transitive dependencies
   * @see BuildRule
   */
  public static <T> DirectedAcyclicGraph<BuildRule> getBuildRuleDirectedGraphFilteredBy(
      Iterable<? extends BuildRule> inputs, Predicate<Object> filter, Predicate<Object> traverse) {

    // Build up a graph of the inputs and their transitive dependencies, we'll use the graph
    // to topologically sort the dependencies.
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
          @Override
          public Iterable<BuildRule> visit(BuildRule rule) {
            if (filter.test(rule)) {
              graph.addNode(rule);
              for (BuildRule dep : rule.getBuildDeps()) {
                if (traverse.test(dep) && filter.test(dep)) {
                  graph.addEdge(rule, dep);
                }
              }
            }
            return traverse.test(rule) ? rule.getBuildDeps() : ImmutableSet.of();
          }
        };
    visitor.start();
    return new DirectedAcyclicGraph<>(graph);
  }
}
