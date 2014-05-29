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

package com.facebook.buck.graph;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class TopologicalSort {

  private TopologicalSort() {}

  public static <T extends Comparable<?>> ImmutableList<T> sort(
      TraversableGraph<T> graph,
      final Predicate<T> inclusionPredicate) {

    // AtomicInteger is used to decrement the integer value in-place.
    Map<T, AtomicInteger> effectiveOutDegreesOfExplorableNodes = Maps.newHashMap();
    Queue<T> nextLevel = Queues.newArrayDeque(graph.getNodesWithNoOutgoingEdges());
    Set<T> visitedNodes = Sets.newHashSet();
    ImmutableList.Builder<T> toReturn = ImmutableList.builder();

    while (!nextLevel.isEmpty()) {
      Queue<T> toExplore = nextLevel;
      nextLevel = Queues.newArrayDeque();

      Set<T> level = Sets.newTreeSet();

      while (!toExplore.isEmpty()) {
        T node = toExplore.remove();
        Preconditions.checkState(
            !visitedNodes.contains(node),
            "The queue of nodes to explore should not contain a node that has already been" +
                " visited.");

        if (inclusionPredicate.apply(node)) {
          level.add(node);
        }
        visitedNodes.add(node);

        // Only add a node to the set of nodes to be explored if all the nodes it depends on have
        // been visited already. We achieve the same by keeping track of the out degrees of
        // explorable nodes. After visiting a node, decrement the out degree of each of its parent
        // node. When the out degree reaches zero, it is safe to add that node to the list of nodes
        // to explore next.
        for (T exploreCandidate : graph.getIncomingNodesFor(node)) {
          if (!effectiveOutDegreesOfExplorableNodes.containsKey(exploreCandidate)) {
            effectiveOutDegreesOfExplorableNodes.put(
                exploreCandidate,
                new AtomicInteger(Iterables.size(graph.getOutgoingNodesFor(exploreCandidate))));
          }
          if (effectiveOutDegreesOfExplorableNodes.get(exploreCandidate).decrementAndGet() == 0) {
            nextLevel.add(exploreCandidate);
          }
        }
      }
      toReturn.addAll(level);
    }

    return toReturn.build();
  }
}
