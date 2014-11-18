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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Queue;
import java.util.Set;

/**
 * Performs a breadth-first traversal of dependencies of a graph node.
 */
public abstract class AbstractBreadthFirstTraversal<Node> {

  private final Queue<Node> toExplore;
  private final Set<Node> explored;

  public AbstractBreadthFirstTraversal(Node initialNode) {
    this(ImmutableSet.of(initialNode));
  }

  public AbstractBreadthFirstTraversal(Iterable<? extends Node> initialNodes) {
    toExplore = Lists.newLinkedList();
    Iterables.addAll(toExplore, initialNodes);
    explored = Sets.newHashSet();
  }

  public final void start() {
    while (!toExplore.isEmpty()) {
      Node currentNode = toExplore.remove();
      if (explored.contains(currentNode)) {
        continue;
      }

      ImmutableSet<Node> depsToVisit = visit(currentNode);
      explored.add(currentNode);

      for (Node dep : depsToVisit) {
        if (!explored.contains(dep)) {
          toExplore.add(dep);
        }
      }
    }

    onComplete();
  }

  /** Override this method with any logic that should be run when {@link #start()} completes. */
  protected void onComplete() {

  }

  /**
   * To perform a full traversal of the the {@code initialNode}'s transitive dependencies, this
   * function should return all of {@code node}'s direct dependencies.
   *
   * @param node Visited graph node
   * @return The set of direct dependencies to visit after visiting this node.
   */
  public abstract ImmutableSet<Node> visit(Node node);
}
