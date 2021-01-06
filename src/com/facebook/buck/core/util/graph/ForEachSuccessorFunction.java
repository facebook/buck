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

import java.util.function.Consumer;

/**
 * A {@link Consumer}-based interface for graph operations/traversals (based on Guava's
 * `SuccessorsFunction`).
 *
 * <p>This interface is meant to be used as a parameter to graph algorithms that only need a way of
 * consuming the successors of a node in a graph.
 */
public interface ForEachSuccessorFunction<N> {

  /**
   * Runs <code>consumer</code> on all nodes in this graph adjacent to node which can be reached by
   * traversing node's outgoing edges in the direction (if any) of the edge.
   *
   * @param node
   * @param consumer
   */
  void forEachSuccessor(N node, Consumer<N> consumer);
}
