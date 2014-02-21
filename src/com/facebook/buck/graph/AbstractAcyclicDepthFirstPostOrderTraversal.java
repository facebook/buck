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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Performs a depth-first, post-order traversal over a DAG.
 * <p>
 * If a cycle is encountered, a {@link CycleException} is thrown by {@link #traverse(Iterable)}.
 * @param <T> the type of node in the graph
 */
public abstract class AbstractAcyclicDepthFirstPostOrderTraversal<T> {

  /**
   * Performs a depth-first, post-order traversal over a DAG.
   * @param initialNodes The nodes from which to perform the traversal. Not allowed to contain
   *     {@code null}.
   * @throws CycleException if a cycle is found while performing the traversal.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public void traverse(Iterable<? extends T> initialNodes) throws CycleException, IOException {
    // This corresponds to the current chain of nodes being explored. Enforcing this invariant makes
    // this data structure useful for debugging.
    Deque<Explorable> toExplore = Lists.newLinkedList();
    for (T node : initialNodes) {
      toExplore.add(new Explorable(node));
    }

    Set<T> inProgress = Sets.newHashSet();
    LinkedHashSet<T> explored = Sets.newLinkedHashSet();

    while (!toExplore.isEmpty()) {
      Explorable explorable = toExplore.peek();
      T node = explorable.node;

      // This could happen if one of the initial nodes is a dependency of the other, for example.
      if (explored.contains(node)) {
        toExplore.removeFirst();
        continue;
      }

      inProgress.add(node);

      // Find children that need to be explored to add to the stack.
      int stackSize = toExplore.size();
      for (Iterator<T> iter = explorable.children; iter.hasNext(); ) {
        T child = iter.next();
        if (inProgress.contains(child)) {
          throw createCycleException(child, toExplore);
        } else if (!explored.contains(child)) {
          toExplore.addFirst(new Explorable(child));

          // Without this break statement:
          // (1) Children will be explored in reverse order instead of the specified order.
          // (2) CycleException may contain extra nodes.
          // Comment out the break statement and run the unit test to verify this for yourself.
          break;
        }
      }

      if (stackSize == toExplore.size()) {
        // Nothing was added to toExplore, so the current node can be popped off the stack and
        // marked as explored.
        toExplore.removeFirst();
        inProgress.remove(node);
        explored.add(node);

        // Now that the internal state of this traversal has been updated, notify the observer.
        onNodeExplored(node);
      }
    }

    Preconditions.checkState(inProgress.isEmpty(), "No more nodes should be in progress.");

    onTraversalComplete(Iterables.unmodifiableIterable(explored));
  }

  /**
   * A node that needs to be explored, paired with a (possibly paused) iteration of its children.
   */
  private class Explorable {
    private final T node;
    private final Iterator<T> children;
    Explorable(T node) throws IOException {
      this.node = Preconditions.checkNotNull(node);
      this.children = findChildren(node);
    }
  }

  /**
   * @return the child nodes of the specified node. Child nodes will be explored in the order
   *     in which they are provided. Not allowed to contain {@code null}.
   */
  protected abstract Iterator<T> findChildren(T node) throws IOException;

  /**
   * Invoked when the specified node has been "explored," which means that all of its transitive
   * dependencies have been visited.
   */
  protected abstract void onNodeExplored(T node);

  /**
   * Upon completion of the traversal, this method is invoked with the nodes in the order they
   * were explored.
   */
  protected abstract void onTraversalComplete(Iterable<T> nodesInExplorationOrder);

  private CycleException createCycleException(T collisionNode,
      Iterable<Explorable> currentExploration) {
    Deque<T> chain = Lists.newLinkedList();
    chain.add(collisionNode);

    boolean foundStartOfCycle = false;
    for (Explorable explorable : currentExploration) {
      T node = explorable.node;
      chain.addFirst(node);
      if (collisionNode.equals(node)) {
        // The start of the cycle has been reached!
        foundStartOfCycle = true;
        break;
      }
    }

    Preconditions.checkState(foundStartOfCycle,
        "Start of cycle %s should appear in traversal history %s.",
        collisionNode,
        chain);

    return new CycleException(chain);
  }

  @SuppressWarnings("serial")
  public static final class CycleException extends Exception {

    private final ImmutableList<?> nodes;

    private CycleException(Iterable<?> nodes) {
      super("Cycle found: " + Joiner.on(" -> ").join(nodes));
      this.nodes = ImmutableList.copyOf(nodes);
    }

    public ImmutableList<?> getCycle() {
      return nodes;
    }
  }
}
