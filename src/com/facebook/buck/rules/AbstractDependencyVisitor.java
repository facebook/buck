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

package com.facebook.buck.rules;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Queue;
import java.util.Set;

/**
 * Performs a breadth-first traversal of a {@link BuildRule}'s dependencies.
 */
public abstract class AbstractDependencyVisitor {

  private final Queue<BuildRule> toExplore;
  private final Set<BuildRule> explored;

  public AbstractDependencyVisitor(BuildRule initialRule) {
    this(initialRule, false /* excludeRoot */);
  }

  public AbstractDependencyVisitor(BuildRule initialRule, boolean excludeRoot) {
    this(excludeRoot ? initialRule.getDeps() : ImmutableSet.of(initialRule));
  }

  public AbstractDependencyVisitor(Collection<BuildRule> initialDeps) {
    toExplore = Lists.newLinkedList();
    toExplore.addAll(initialDeps);
    explored = Sets.newHashSet();
  }

  public final void start() {
    while (!toExplore.isEmpty()) {
      BuildRule currentRule = toExplore.remove();
      if (explored.contains(currentRule)) {
        continue;
      }

      ImmutableSet<BuildRule> depsToVisit = visit(currentRule);
      explored.add(currentRule);

      for (BuildRule dep : depsToVisit) {
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
   * To perform a full traversal of the {@code rule}'s transitive dependencies, this rule
   * should return {@code rule.getDeps()}.
   *
   * @param rule Visited build rule
   * @return The set of children to visit after visiting this node.  This set must be a subset of
   *    {@code rule.getDeps()}
   */
  public abstract ImmutableSet<BuildRule> visit(BuildRule rule);

  public static ImmutableSet<BuildRule> maybeVisitAllDeps(BuildRule rule, boolean visitDeps) {
    return visitDeps ? rule.getDeps() : ImmutableSet.<BuildRule>of();
  }
}
