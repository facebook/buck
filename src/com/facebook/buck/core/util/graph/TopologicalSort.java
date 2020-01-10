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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Queues;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class TopologicalSort {

  /** User provider callback used for walking the graph */
  public interface Traversable<T> {
    Iterator<? extends T> findChildren(T node);
  }

  private TopologicalSort() {}

  /** Returns a topologically sorted list of the nodes in the graph. */
  public static <T> ImmutableList<T> sort(TraversableGraph<T> graph) {
    return sortImpl(graph, LinkedHashSet::new);
  }

  /** Returns a topologically sorted list of all nodes in the graph. */
  public static <T> ImmutableList<? extends T> sort(
      Iterable<? extends T> roots, Traversable<T> traversable) {
    return sortTraversableImpl(roots, traversable, LinkedHashSet::new);
  }

  /**
   * This special form of topological sort returns items with each "level" sorted. The algorithm is
   * basically this: identify all the leaves of the graph, sort them, add them to the list, remove
   * them from the graph and proceed.
   *
   * @deprecated This is silly. You should just use the normal sort. This is here because c/c++ link
   *     command lines are sensitive to argument order, but aren't strict about it being correct.
   *     Since they aren't strict, code has evolved to depend on the specific order that Buck has
   *     been ordering link lines, and so this preserves that legacy behavior until code that
   *     depends on it is fixed.
   */
  // TODO(agallagher): delete this.
  @Deprecated
  public static <T> ImmutableList<? extends T> snowflakeSort(
      Iterable<? extends T> roots, Traversable<T> traversable, Comparator<T> comparator) {
    return sortTraversableImpl(roots, traversable, () -> new TreeSet<>(comparator));
  }

  private static <T> ImmutableList<? extends T> sortTraversableImpl(
      Iterable<? extends T> roots, Traversable<T> traversable, Supplier<Set<T>> levelSetFactory) {
    MutableDirectedGraph<T> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<T> visitor =
        new AbstractBreadthFirstTraversal<T>(roots) {
          @Override
          public ImmutableSet<T> visit(T node) {
            graph.addNode(node);

            // Process all the traversable deps.
            ImmutableSet.Builder<T> deps = ImmutableSet.builder();
            traversable
                .findChildren(node)
                .forEachRemaining(
                    dep -> {
                      graph.addEdge(node, dep);
                      deps.add(dep);
                    });
            return deps.build();
          }
        };
    visitor.start();
    return sortImpl(graph, levelSetFactory);
  }

  // TODO(cjhopman): The implementations here aren't great and should be improved and migrated to
  // GraphTraversables (probably).
  private static <T> ImmutableList<T> sortImpl(
      TraversableGraph<T> graph, Supplier<Set<T>> levelSetFactory) {
    // AtomicInteger is used to decrement the integer value in-place.
    Map<T, AtomicInteger> effectiveOutDegreesOfExplorableNodes = new HashMap<>();
    Queue<T> nextLevel = Queues.newArrayDeque(graph.getNodesWithNoOutgoingEdges());
    Set<T> visitedNodes = new HashSet<>();
    ImmutableList.Builder<T> toReturn = ImmutableList.builder();

    while (!nextLevel.isEmpty()) {
      Queue<T> toExplore = nextLevel;
      nextLevel = Queues.newArrayDeque();

      Set<T> level = levelSetFactory.get();

      while (!toExplore.isEmpty()) {
        T node = toExplore.remove();
        Preconditions.checkState(
            !visitedNodes.contains(node),
            "The queue of nodes to explore should not contain a node that has already been"
                + " visited.");

        level.add(node);
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
