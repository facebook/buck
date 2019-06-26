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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public class NativeLinkableGroups {

  private NativeLinkableGroups() {}

  /**
   * Find {@link NativeLinkableGroup} nodes transitively reachable from the given roots.
   *
   * @param from the starting set of roots to begin the search from.
   * @param passthrough a {@link Function} determining acceptable dependencies to traverse when
   *     searching for {@link NativeLinkableGroup}s.
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkableGroup}.
   */
  public static <T> ImmutableMap<BuildTarget, NativeLinkableGroup> getNativeLinkableRoots(
      Iterable<? extends T> from,
      Function<? super T, Optional<Iterable<? extends T>>> passthrough) {
    ImmutableMap.Builder<BuildTarget, NativeLinkableGroup> nativeLinkables = ImmutableMap.builder();

    AbstractBreadthFirstTraversal<T> visitor =
        new AbstractBreadthFirstTraversal<T>(from) {
          @Override
          public Iterable<? extends T> visit(T rule) {

            // If this is a passthrough rule, just continue on to its deps.
            Optional<Iterable<? extends T>> deps = passthrough.apply(rule);
            if (deps.isPresent()) {
              return deps.get();
            }

            // If this is `NativeLinkable`, we've found a root so record the rule and terminate
            // the search.
            if (rule instanceof NativeLinkableGroup) {
              NativeLinkableGroup nativeLinkableGroup = (NativeLinkableGroup) rule;
              nativeLinkables.put(nativeLinkableGroup.getBuildTarget(), nativeLinkableGroup);
              return ImmutableSet.of();
            }

            // Otherwise, terminate the search.
            return ImmutableSet.of();
          }
        };
    visitor.start();

    return nativeLinkables.build();
  }

  /** @return the nodes found from traversing the given roots in topologically sorted order. */
  public static ImmutableList<? extends NativeLinkableGroup> getTopoSortedNativeLinkables(
      Iterable<? extends NativeLinkableGroup> roots,
      GraphTraversable<NativeLinkableGroup> traversable) {
    // Topologically sort the rules.
    return TopologicalSort.snowflakeSort(
            roots, traversable, Comparator.comparing(NativeLinkableGroup::getBuildTarget))
        .reverse();
  }

  /**
   * @return the first-order dependencies to consider when linking the given {@link
   *     NativeLinkableGroup}.
   */
  private static Iterable<? extends NativeLinkableGroup> getDepsForLink(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      NativeLinkableGroup nativeLinkableGroup,
      LinkableDepType linkStyle) {

    // We always traverse a rule's exported native linkables.
    Iterable<? extends NativeLinkableGroup> nativeLinkableDeps =
        nativeLinkableGroup.getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder);

    boolean shouldTraverse;
    switch (nativeLinkableGroup.getPreferredLinkage(cxxPlatform)) {
      case ANY:
        shouldTraverse = linkStyle != Linker.LinkableDepType.SHARED;
        break;
      case SHARED:
        shouldTraverse = false;
        break;
        // $CASES-OMITTED$
      default:
        shouldTraverse = true;
        break;
    }

    // If we're linking this dependency statically, we also need to traverse its deps.
    if (shouldTraverse) {
      nativeLinkableDeps =
          Iterables.concat(
              nativeLinkableDeps,
              nativeLinkableGroup.getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder));
    }

    return nativeLinkableDeps;
  }

  /**
   * Extract from the dependency graph all the libraries which must be considered for linking.
   *
   * <p>Traversal proceeds depending on whether each dependency is to be statically or dynamically
   * linked.
   *
   * @param linkStyle how dependencies should be linked, if their preferred_linkage is {@code
   *     NativeLinkable.Linkage.ANY}.
   */
  public static ImmutableList<? extends NativeLinkableGroup> getNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkableGroup> inputs,
      LinkableDepType linkStyle,
      Predicate<? super NativeLinkableGroup> traverse) {
    return getTopoSortedNativeLinkables(
        inputs,
        nativeLinkable ->
            FluentIterable.from(
                    getDepsForLink(cxxPlatform, graphBuilder, nativeLinkable, linkStyle))
                .transform(NativeLinkableGroup.class::cast)
                .filter(traverse::test)
                .iterator());
  }

  public static ImmutableList<? extends NativeLinkableGroup> getNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkableGroup> inputs,
      LinkableDepType linkStyle) {
    return getNativeLinkables(cxxPlatform, graphBuilder, inputs, linkStyle, x -> true);
  }

  public static Linker.LinkableDepType getLinkStyle(
      NativeLinkableGroup.Linkage preferredLinkage, Linker.LinkableDepType requestedLinkStyle) {
    Linker.LinkableDepType linkStyle;
    switch (preferredLinkage) {
      case SHARED:
        linkStyle = Linker.LinkableDepType.SHARED;
        break;
      case STATIC:
        linkStyle =
            requestedLinkStyle == Linker.LinkableDepType.STATIC
                ? Linker.LinkableDepType.STATIC
                : Linker.LinkableDepType.STATIC_PIC;
        break;
      case ANY:
        linkStyle = requestedLinkStyle;
        break;
      default:
        throw new IllegalStateException();
    }
    return linkStyle;
  }

  public static NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle,
      NativeLinkableGroup nativeLinkableGroup,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    NativeLinkableGroup.Linkage link = nativeLinkableGroup.getPreferredLinkage(cxxPlatform);
    return nativeLinkableGroup.getNativeLinkableInput(
        cxxPlatform, getLinkStyle(link, linkStyle), graphBuilder, targetConfiguration);
  }

  public static ImmutableMap<BuildTarget, NativeLinkableGroup> getTransitiveNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkableGroup> inputs) {

    Map<BuildTarget, NativeLinkableGroup> nativeLinkables = new HashMap<>();
    for (NativeLinkableGroup nativeLinkableGroup : inputs) {
      nativeLinkables.put(nativeLinkableGroup.getBuildTarget(), nativeLinkableGroup);
    }

    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public Iterable<BuildTarget> visit(BuildTarget target) {
            NativeLinkableGroup nativeLinkableGroup =
                Objects.requireNonNull(nativeLinkables.get(target));
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkableGroup dep :
                Iterables.concat(
                    nativeLinkableGroup.getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
                    nativeLinkableGroup.getNativeLinkableExportedDepsForPlatform(
                        cxxPlatform, graphBuilder))) {
              BuildTarget depTarget = dep.getBuildTarget();
              deps.add(depTarget);
              nativeLinkables.put(depTarget, dep);
            }
            return deps.build();
          }
        };
    visitor.start();

    return ImmutableMap.copyOf(nativeLinkables);
  }
}
