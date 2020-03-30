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

import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link Consumer}-based graph traversers.
 *
 * <p>Implements various graph traversals for graphs represented by {@link
 * ForEachSuccessorFunction}. This may be desirable to use instead of {@code Abstract*Traversal}s in
 * cases where the overhead of buffering node dependencies in streams/iterables/collections needs to
 * be avoided (as node dependencies are discovered/consumed without per-node buffering).
 *
 * <p>The traversals generally avoid extra bookkeeping to detect graph cycles, and so may silently
 * produce undefined results in this case.
 */
public abstract class ConsumingTraverser<N> {

  public abstract void forEach(Consumer<N> consumer);

  public final <E extends Exception> void forEachThrowing(ThrowingConsumer<N, E> consumer)
      throws E {
    ThrowingConsumer.wrapAsUnchecked(this::forEach, consumer);
  }

  /**
   * Consume nodes via {@code consumer}, starting from {@code startNodes}, in a breadth-first walk
   * of the graph.
   *
   * <p>Allocates a set of O(#nodes) to record visited nodes and a queue of O(width).
   */
  public static <N> ConsumingTraverser<N> breadthFirst(
      Iterable<? extends N> startNodes, ForEachSuccessorFunction<N> successors) {
    return new ConsumingTraverser<N>() {
      @Override
      public void forEach(Consumer<N> consumer) {
        Queue<N> nodesToProcess = new ArrayDeque<>();
        Set<N> visited = new HashSet<>();
        Consumer<N> processNode =
            node -> {
              if (visited.add(node)) {
                nodesToProcess.offer(node);
              }
            };
        startNodes.forEach(processNode);
        while (!nodesToProcess.isEmpty()) {
          N node = nodesToProcess.remove();
          consumer.accept(node);
          successors.forEachSuccessor(node, processNode);
        }
      }
    };
  }

  /**
   * Consume nodes via {@code consumer}, starting from {@code startNodes}, in a topologically sorted
   * order.
   *
   * @return a {@link ConsumingTraverser} for a graph represented by the given {@link
   *     ForEachSuccessorFunction}.
   *     <p>Allocates a set of O(#nodes) to record visited nodes, a map of (#nodes) to count
   *     incoming edges, and a queue of nodes to process. Calls each node's successor consumer
   *     function twice.
   */
  public static <N> ConsumingTraverser<N> topologicallySorted(
      Iterable<? extends N> startNodes, ForEachSuccessorFunction<N> successors) {
    return new ConsumingTraverser<N>() {
      @Override
      public void forEach(Consumer<N> consumer) {
        Set<N> visited = new HashSet<>();
        HashMultiset<N> incomingEdges = HashMultiset.create();
        Queue<N> nodesToProcess = new ArrayDeque<>();

        // Do an initial pass to count incoming edges.
        Consumer<N> processDep =
            node -> {
              incomingEdges.add(node);
              nodesToProcess.offer(node);
            };
        startNodes.forEach(nodesToProcess::add);
        while (!nodesToProcess.isEmpty()) {
          N node = nodesToProcess.remove();
          if (visited.add(node)) {
            successors.forEachSuccessor(node, processDep);
          }
        }

        // Setup a consumer that we'll apply to all deps in the second pass.  This will decrement
        // the incoming edges count and add it to the queue if it's zero.
        Consumer<N> enqueueDeps =
            node -> {
              if (incomingEdges.remove(node, 1) == 1) {
                nodesToProcess.offer(node);
              }
            };
        for (N node : startNodes) {
          if (incomingEdges.count(node) == 0) {
            nodesToProcess.add(node);
          }
        }
        while (!nodesToProcess.isEmpty()) {
          N node = nodesToProcess.remove();
          if (visited.remove(node)) {
            consumer.accept(node);
            successors.forEachSuccessor(node, enqueueDeps);
          }
        }

        // At this point, there should be no remaining edges to explore.
        Preconditions.checkState(incomingEdges.isEmpty(), "Cycle detected in graph");
      }
    };
  }
}
