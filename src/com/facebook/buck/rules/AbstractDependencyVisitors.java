/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.graph.DefaultImmutableDirectedAcyclicGraph;
import com.facebook.buck.graph.ImmutableDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.google.common.collect.ImmutableSet;

public class AbstractDependencyVisitors {
  private AbstractDependencyVisitors() {
  }

  /**
   * Given dependencies in inputs builds graph of transitive dependencies filtering them by
   * instanceOf T.
   *
   * @param inputs      initial dependencies from which to build transitive closure
   * @param typeFilter  class to filter with on instanceOf, we need this because of
   *                    the type erasure
   * @param <T>         class to fitler on
   * @return            filtered BuildRule DAG of transitive dependencies
   *
   * @see com.facebook.buck.rules.BuildRule
   */
  public static <T> ImmutableDirectedAcyclicGraph<BuildRule> getBuildRuleDirectedGraphFilteredBy(
      final Iterable<? extends BuildRule> inputs, final Class<T> typeFilter) {
    // Build up a graph of the inputs and their transitive dependencies, we'll use the graph
    // to topologically sort the dependencies.
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(inputs) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (typeFilter.isAssignableFrom(rule.getClass())) {
          graph.addNode(rule);
          for (BuildRule dep : rule.getDeps()) {
            if (typeFilter.isAssignableFrom(dep.getClass())) {
              graph.addEdge(rule, dep);
            }
          }
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    };
    visitor.start();
    return new DefaultImmutableDirectedAcyclicGraph<BuildRule>(graph);
  }
}
