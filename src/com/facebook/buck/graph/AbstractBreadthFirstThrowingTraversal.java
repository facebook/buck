/*
 * Copyright 2015-present Facebook, Inc.
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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/** Performs a breadth-first traversal of dependencies of a graph node. */
public abstract class AbstractBreadthFirstThrowingTraversal<Node, E extends Throwable> {

  private final Queue<Node> toExplore;
  private final Set<Node> explored;

  public AbstractBreadthFirstThrowingTraversal(Node initialNode) {
    this(ImmutableSet.of(initialNode));
  }

  public AbstractBreadthFirstThrowingTraversal(Iterable<? extends Node> initialNodes) {
    toExplore = new LinkedList<>();
    Iterables.addAll(toExplore, initialNodes);
    explored = new HashSet<>();
  }

  public final void start() throws E {
    while (!toExplore.isEmpty()) {
      Node currentNode = toExplore.remove();
      if (explored.contains(currentNode)) {
        continue;
      }

      Iterable<? extends Node> depsToVisit = this.visit(currentNode);
      explored.add(currentNode);

      for (Node dep : depsToVisit) {
        if (!explored.contains(dep)) {
          toExplore.add(dep);
        }
      }
    }

    this.onComplete();
  }

  /** Override this method with any logic that should be run when {@link #start()} completes. */
  protected void onComplete() throws E {}

  /**
   * To perform a full traversal of the the {@code initialNode}'s transitive dependencies, this
   * function should return all of {@code node}'s direct dependencies.
   *
   * @param node Visited graph node
   * @return The set of direct dependencies to visit after visiting this node.
   */
  public abstract Iterable<? extends Node> visit(Node node) throws E;

  /**
   * This will typically be implemented as a lambda passed to {@link #traverse(Object, Visitor)} or
   * {@link #traverse(Iterable, Visitor)}
   */
  public interface Visitor<Node, E extends Throwable> {
    Iterable<Node> visit(Node node) throws E;
  }

  protected static class StaticBreadthFirstTraversal<Node>
      extends AbstractBreadthFirstThrowingTraversal<Node, RuntimeException> {

    private final Visitor<Node, RuntimeException> visitor;

    protected StaticBreadthFirstTraversal(
        Node initialNode, Visitor<Node, RuntimeException> visitor) {
      super(initialNode);
      this.visitor = visitor;
    }

    protected StaticBreadthFirstTraversal(
        Iterable<? extends Node> initialNodes, Visitor<Node, RuntimeException> visitor) {
      super(initialNodes);
      this.visitor = visitor;
    }

    @Override
    public Iterable<Node> visit(Node node) throws RuntimeException {
      return visitor.visit(node);
    }
  }

  /**
   * Traverse a graph without explicitly creating a {@code new
   * AbstractBreadthFirstThrowingTraversal} and overriding {@link #visit(Object)}
   *
   * @param visitor Typically a lambda expression
   */
  public static <Node> void traverse(Node initialNode, Visitor<Node, RuntimeException> visitor) {
    new StaticBreadthFirstTraversal<>(initialNode, visitor).start();
  }

  /**
   * Traverse a graph without explicitly creating a {@code new
   * AbstractBreadthFirstThrowingTraversal} and overriding {@link #visit(Object)}
   *
   * @param visitor Typically a lambda expression
   */
  public static <Node> void traverse(
      Iterable<? extends Node> initialNodes, final Visitor<Node, RuntimeException> visitor) {
    new StaticBreadthFirstTraversal<>(initialNodes, visitor).start();
  }
}
