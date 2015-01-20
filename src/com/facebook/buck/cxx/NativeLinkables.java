/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

public class NativeLinkables {

  private NativeLinkables() {}

  /**
   * A helper function object that grabs the {@link NativeLinkableInput} object from a
   * {@link NativeLinkable}.
   */
  public static Function<NativeLinkable, NativeLinkableInput> getNativeLinkableInput(
      final CxxPlatform cxxPlatform,
      final Linker.LinkableDepType type) {
    return new Function<NativeLinkable, NativeLinkableInput>() {
      @Override
      public NativeLinkableInput apply(NativeLinkable input) {
        return input.getNativeLinkableInput(cxxPlatform, type);
      }
    };
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of
   * {@link com.facebook.buck.cxx.NativeLinkable} objects found via the passed in
   * {@link com.facebook.buck.rules.BuildRule} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Linker.LinkableDepType depType,
      final Predicate<Object> traverse,
      boolean reverse) {

    final DirectedAcyclicGraph<BuildRule> graph =
        BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
            inputs,
            Predicates.instanceOf(NativeLinkable.class),
            traverse);

    // Collect and topologically sort our deps that contribute to the link.
    final ImmutableList<BuildRule> sorted = TopologicalSort.sort(
        graph,
        Predicates.<BuildRule>alwaysTrue());
    return NativeLinkableInput.concat(
        FluentIterable
            .from(reverse ? sorted.reverse() : sorted)
            .filter(NativeLinkable.class)
            .transform(getNativeLinkableInput(cxxPlatform, depType)));
  }

  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      final CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      final Linker.LinkableDepType depType,
      boolean reverse) {
    return getTransitiveNativeLinkableInput(
        cxxPlatform,
        inputs,
        depType,
        Predicates.instanceOf(NativeLinkable.class),
        reverse);
  }

}
