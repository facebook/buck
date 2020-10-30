/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.util.graph;

import com.google.common.collect.ImmutableSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TraversableGraphs {

  private TraversableGraphs() {}

  /**
   * For this graph, returns the set of strongly connected components using Tarjan's algorithm. Note
   * this is {@code O(|V| + |E|)}.
   *
   * @return an unmodifiable {@link Set} of sets, each of which is also an unmodifiable {@link Set}
   *     and represents a strongly connected component.
   */
  public static <T> ImmutableSet<ImmutableSet<T>> findStronglyConnectedComponents(
      TraversableGraph<T> graph) {
    TraversableGraphs.Tarjan<T> tarjan = new TraversableGraphs.Tarjan<>(graph);
    return tarjan.findStronglyConnectedComponents();
  }

  /**
   * Implementation of
   * http://en.wikipedia.org/wiki/Tarjan%E2%80%99s_strongly_connected_components_algorithm used to
   * find cycles in the graph.
   */
  static class Tarjan<S> {
    private final TraversableGraph<S> graph;
    private final Map<S, Integer> indexes;
    private final Map<S, Integer> lowlinks;
    private final Deque<S> nodeStack;
    private final ImmutableSet.Builder<ImmutableSet<S>> stronglyConnectedComponents;
    private int index;

    private Tarjan(TraversableGraph<S> graph) {
      this.graph = graph;
      this.indexes = new HashMap<>();
      this.lowlinks = new HashMap<>();
      this.nodeStack = new LinkedList<>();
      this.stronglyConnectedComponents = ImmutableSet.builder();
      this.index = 0;
    }

    public ImmutableSet<ImmutableSet<S>> findStronglyConnectedComponents() {
      for (S node : graph.getNodes()) {
        if (!indexes.containsKey(node)) {
          doStrongConnect(node);
        }
      }
      return stronglyConnectedComponents.build();
    }

    private void doStrongConnect(S node) {
      // Set the depth index for node to the smallest unused index.
      indexes.put(node, index);
      lowlinks.put(node, index);
      index++;
      nodeStack.push(node);

      // Consider successors of node.
      for (S sink : graph.getOutgoingNodesFor(node)) {
        if (!indexes.containsKey(sink)) {
          doStrongConnect(sink);
          int lowlink =
              Math.min(
                  Objects.requireNonNull(lowlinks.get(node)),
                  Objects.requireNonNull(lowlinks.get(sink)));
          lowlinks.put(node, lowlink);
        } else if (nodeStack.contains(sink)) {
          // TODO(mbolin): contains() is O(N), consider maintaining an index so it is O(1)?
          int lowlink =
              Math.min(
                  Objects.requireNonNull(lowlinks.get(node)),
                  Objects.requireNonNull(indexes.get(sink)));
          lowlinks.put(node, lowlink);
        }
      }

      // If node is a root node, then pop the stack and generate a strongly connected component.
      if (Objects.requireNonNull(lowlinks.get(node)).equals(indexes.get(node))) {
        ImmutableSet.Builder<S> stronglyConnectedComponent = ImmutableSet.builder();
        S componentElement;
        do {
          componentElement = nodeStack.pop();
          stronglyConnectedComponent.add(componentElement);
        } while (componentElement != node);
        stronglyConnectedComponents.add(stronglyConnectedComponent.build());
      }
    }
  }
}
