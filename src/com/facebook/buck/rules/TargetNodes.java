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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasTests;

import com.google.common.collect.ImmutableSortedSet;

/**
 * Utility class to work with {@link TargetNode} objects.
 */
public class TargetNodes {
  // Utility class, do not instantiate.
  private TargetNodes() { }

  /**
   * If {@code node} refers to a node which contains references to its
   * tests, returns the tests associated with that node.
   *
   * Otherwise, returns an empty set.
   */
  public static ImmutableSortedSet<BuildTarget> getTestTargetsForNode(TargetNode<?> node) {
    if (node.getConstructorArg() instanceof HasTests) {
      return ((HasTests) node.getConstructorArg()).getTests();
    } else {
      return ImmutableSortedSet.of();
    }
  }
}
