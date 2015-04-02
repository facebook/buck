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

package com.facebook.buck.apple;

import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

public class AppleResources {
  // Utility class, do not instantiate.
  private AppleResources() { }

  /**
   * Collect resources from recursive dependencies.
   *
   * @param targetGraph The {@link TargetGraph} containing the node and its dependencies.
   * @param targetNodes {@link TargetNode} at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  public static <T> ImmutableSet<AppleResourceDescription.Arg> collectRecursiveResources(
      final TargetGraph targetGraph,
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.COPYING,
                ImmutableSet.of(AppleResourceDescription.TYPE)))
        .transform(
            new Function<TargetNode<?>, AppleResourceDescription.Arg>() {
              @Override
              public AppleResourceDescription.Arg apply(TargetNode<?> input) {
                return (AppleResourceDescription.Arg) input.getConstructorArg();
              }
            })
        .toSet();
  }
}
