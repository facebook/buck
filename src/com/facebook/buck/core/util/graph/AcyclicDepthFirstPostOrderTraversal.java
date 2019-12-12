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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.Iterables;
import java.util.function.Predicate;

/**
 * Performs a depth-first, post-order traversal over a DAG.
 *
 * <p>If a cycle is encountered, a {@link CycleException} is thrown by {@link #traverse(Iterable)}.
 *
 * @param <T> the type of node in the graph
 */
public class AcyclicDepthFirstPostOrderTraversal<T> {

  private final AcyclicDepthFirstPostOrderTraversalWithPayloadAndDependencyStack<T, Unit> traversal;

  public AcyclicDepthFirstPostOrderTraversal(GraphTraversable<T> traversable) {
    this.traversal =
        new AcyclicDepthFirstPostOrderTraversalWithPayloadAndDependencyStack<>(
            (n, dependencyStack) -> {
              return new Pair<>(Unit.UNIT, traversable.findChildren(n));
            },
            (dependencyStack, t) -> DependencyStack.root());
  }

  public Iterable<T> traverse(Iterable<? extends T> initialNodes) throws CycleException {
    return traverse(initialNodes, node -> true);
  }

  /**
   * Performs a depth-first, post-order traversal over a DAG.
   *
   * @param initialNodes The nodes from which to perform the traversal. Not allowed to contain
   *     {@code null}.
   * @param shouldExploreChildren Whether or not to explore a particular node's children. Used to
   *     support short circuiting in the traversal.
   * @throws CycleException if a cycle is found while performing the traversal.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public Iterable<T> traverse(
      Iterable<? extends T> initialNodes, Predicate<T> shouldExploreChildren)
      throws CycleException {
    return Iterables.unmodifiableIterable(
        traversal.traverse(initialNodes, shouldExploreChildren).keySet());
  }
}
