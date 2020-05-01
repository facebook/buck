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

package com.facebook.buck.core.rules.config.graph;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.google.common.annotations.VisibleForTesting;
import javax.annotation.Nullable;

/**
 * Similar to {@link com.facebook.buck.core.exceptions.DependencyStack} but:
 *
 * <ul>
 *   <li>Restricted to build targets
 *   <li>Asserts no duplicates
 *   <li>Used to detect cycles in configuration graph
 * </ul>
 *
 * This class is used to detect cycles in configuration graph.
 */
public class ConfigurationGraphDependencyStack {

  @Nullable private final ConfigurationGraphDependencyStack parent;
  private final DependencyStack dependencyStack;
  @Nullable private final UnflavoredBuildTarget target;

  private ConfigurationGraphDependencyStack(
      @Nullable ConfigurationGraphDependencyStack parent,
      DependencyStack dependencyStack,
      @Nullable UnflavoredBuildTarget target) {
    this.parent = parent;
    this.dependencyStack = dependencyStack;
    this.target = target;
  }

  public static ConfigurationGraphDependencyStack root(DependencyStack dependencyStack) {
    return new ConfigurationGraphDependencyStack(null, dependencyStack, null);
  }

  /** Check is the configuration stack contains an element. */
  @VisibleForTesting
  boolean contains(UnflavoredBuildTarget target) {
    // Note this operation is linear.
    // That works fine, because configuration graph dependency stacks
    // rarely exceed 10 elements.
    // If this become an issue, we can store elements index in a tree
    // for log(N) lookup or use a bloom filter.

    ConfigurationGraphDependencyStack stack;
    for (stack = this; stack != null; stack = stack.parent) {
      if (stack.target != null && stack.target.equals(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Put element on top of the stack.
   *
   * <p>Throw if this stack (but not attached {@link DependencyStack} form a cycle.
   */
  public ConfigurationGraphDependencyStack child(UnflavoredBuildTarget target) {
    if (contains(target)) {
      throw new HumanReadableException(
          dependencyStack.child(target), "cycle detected when resolving configuration rule");
    }
    return new ConfigurationGraphDependencyStack(this, dependencyStack.child(target), target);
  }

  /** Get a dependency stack constructed from original dependency stack and this graph stack. */
  public DependencyStack getDependencyStack() {
    return dependencyStack;
  }

  /**
   * Put element on top of the stack.
   *
   * <p>Throw if this stack (but not attached {@link DependencyStack} form a cycle.
   */
  public ConfigurationGraphDependencyStack child(UnconfiguredBuildTarget target) {
    return child(target.getUnflavoredBuildTarget());
  }
}
