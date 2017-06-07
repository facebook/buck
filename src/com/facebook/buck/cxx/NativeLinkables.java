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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class NativeLinkables {

  private NativeLinkables() {}

  /**
   * Find all {@link NativeLinkable} transitive roots reachable from the given {@link BuildRule}s.
   *
   * @param from the starting set of {@link BuildRule}s to begin the search from.
   * @param traverse a {@link Predicate} determining acceptable dependencies to traverse when
   *     searching for {@link NativeLinkable}s.
   * @param skip Skip this {@link BuildRule} even if it is an instance of {@link NativeLinkable}
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkable}.
   */
  static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkableRoots(
      Iterable<? extends BuildRule> from,
      final Predicate<Object> traverse,
      final Predicate<Object> skip) {

    final ImmutableMap.Builder<BuildTarget, NativeLinkable> nativeLinkables =
        ImmutableMap.builder();
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(from) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {

            // If this is `NativeLinkable`, we've found a root so record the rule and terminate
            // the search.
            if (rule instanceof NativeLinkable && !skip.apply(rule)) {
              NativeLinkable nativeLinkable = (NativeLinkable) rule;
              nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
              return ImmutableSet.of();
            }

            // Otherwise, make sure this rule is marked as traversable before following it's deps.
            if (traverse.apply(rule)) {
              return rule.getBuildDeps();
            }

            return ImmutableSet.of();
          }
        };
    visitor.start();

    return nativeLinkables.build();
  }

  /**
   * Find all {@link NativeLinkable} transitive roots reachable from the given {@link BuildRule}s.
   *
   * @param from the starting set of {@link BuildRule}s to begin the search from.
   * @param traverse a {@link Predicate} determining acceptable dependencies to traverse when
   *     searching for {@link NativeLinkable}s.
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkable}.
   */
  public static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkableRoots(
      Iterable<? extends BuildRule> from, final Predicate<Object> traverse) {
    return getNativeLinkableRoots(from, traverse, x -> false);
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
  public static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkables(
      final CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> inputs,
      final Linker.LinkableDepType linkStyle,
      final Predicate<? super NativeLinkable> traverse) {

    final Map<BuildTarget, NativeLinkable> nativeLinkables = new HashMap<>();
    for (NativeLinkable nativeLinkable : inputs) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    final MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public ImmutableSet<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);

            // We always traverse a rule's exported native linkables.
            Iterable<? extends NativeLinkable> nativeLinkableDeps =
                nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform);

            boolean shouldTraverse = true;
            switch (nativeLinkable.getPreferredLinkage(cxxPlatform)) {
              case ANY:
                shouldTraverse = linkStyle != Linker.LinkableDepType.SHARED;
                break;
              case SHARED:
                shouldTraverse = false;
                break;
              case STATIC:
                shouldTraverse = true;
                break;
            }

            // If we're linking this dependency statically, we also need to traverse its deps.
            if (shouldTraverse) {
              nativeLinkableDeps =
                  Iterables.concat(
                      nativeLinkableDeps,
                      nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform));
            }

            // Process all the traversable deps.
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep : nativeLinkableDeps) {
              if (traverse.apply(dep)) {
                BuildTarget depTarget = dep.getBuildTarget();
                graph.addEdge(target, depTarget);
                deps.add(depTarget);
                nativeLinkables.put(depTarget, dep);
              }
            }
            return deps.build();
          }
        };
    visitor.start();

    // Topologically sort the rules.
    Iterable<BuildTarget> ordered = TopologicalSort.sort(graph).reverse();

    // Return a map of of the results.
    ImmutableMap.Builder<BuildTarget, NativeLinkable> result = ImmutableMap.builder();
    for (BuildTarget target : ordered) {
      result.put(target, nativeLinkables.get(target));
    }
    return result.build();
  }

  public static ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkables(
      final CxxPlatform cxxPlatform,
      Iterable<? extends NativeLinkable> inputs,
      final Linker.LinkableDepType linkStyle) {
    return getNativeLinkables(cxxPlatform, inputs, linkStyle, x -> true);
  }

  public static Linker.LinkableDepType getLinkStyle(
      NativeLinkable.Linkage preferredLinkage, Linker.LinkableDepType requestedLinkStyle) {
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
      CxxPlatform cxxPlatform, Linker.LinkableDepType linkStyle, NativeLinkable nativeLinkable)
      throws NoSuchBuildTargetException {
    NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform);
    return nativeLinkable.getNativeLinkableInput(cxxPlatform, getLinkStyle(link, linkStyle));
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of {@link
   * com.facebook.buck.cxx.NativeLinkable} objects found via the passed in {@link
   * com.facebook.buck.rules.BuildRule} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      Predicate<Object> traverse,
      Predicate<Object> skip)
      throws NoSuchBuildTargetException {

    // Get the topologically sorted native linkables.
    ImmutableMap<BuildTarget, NativeLinkable> roots =
        getNativeLinkableRoots(inputs, traverse, skip);
    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
        getNativeLinkables(cxxPlatform, roots.values(), depType);
    ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
    for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
      nativeLinkableInputs.add(getNativeLinkableInput(cxxPlatform, depType, nativeLinkable));
    }
    return NativeLinkableInput.concat(nativeLinkableInputs.build());
  }

  public static ImmutableMap<BuildTarget, NativeLinkable> getTransitiveNativeLinkables(
      final CxxPlatform cxxPlatform, Iterable<? extends NativeLinkable> inputs) {

    final Map<BuildTarget, NativeLinkable> nativeLinkables = new HashMap<>();
    for (NativeLinkable nativeLinkable : inputs) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    final MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public ImmutableSet<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep :
                Iterables.concat(
                    nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform),
                    nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform))) {
              BuildTarget depTarget = dep.getBuildTarget();
              graph.addEdge(target, depTarget);
              deps.add(depTarget);
              nativeLinkables.put(depTarget, dep);
            }
            return deps.build();
          }
        };
    visitor.start();

    return ImmutableMap.copyOf(nativeLinkables);
  }

  /**
   * Collect up and merge all {@link com.facebook.buck.cxx.NativeLinkableInput} objects from
   * transitively traversing all unbroken dependency chains of {@link
   * com.facebook.buck.cxx.NativeLinkable} objects found via the passed in {@link
   * com.facebook.buck.rules.BuildRule} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Linker.LinkableDepType depType,
      Predicate<Object> traverse)
      throws NoSuchBuildTargetException {
    return getTransitiveNativeLinkableInput(cxxPlatform, inputs, depType, traverse, x -> false);
  }

  /**
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link com.facebook.buck.cxx.NativeLinkable}
   * objects found via the passed in {@link com.facebook.buck.rules.BuildRule} roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      CxxPlatform cxxPlatform,
      Iterable<? extends BuildRule> inputs,
      Predicate<Object> traverse,
      Predicate<Object> skip)
      throws NoSuchBuildTargetException {

    ImmutableMap<BuildTarget, NativeLinkable> roots =
        getNativeLinkableRoots(inputs, traverse, skip);
    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
        getTransitiveNativeLinkables(cxxPlatform, roots.values());

    Map<String, SourcePath> libraries = new LinkedHashMap<>();
    for (NativeLinkable nativeLinkable : nativeLinkables.values()) {
      NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
      if (linkage != NativeLinkable.Linkage.STATIC) {
        ImmutableMap<String, SourcePath> libs = nativeLinkable.getSharedLibraries(cxxPlatform);
        for (Map.Entry<String, SourcePath> lib : libs.entrySet()) {
          SourcePath prev = libraries.put(lib.getKey(), lib.getValue());
          if (prev != null && !prev.equals(lib.getValue())) {
            throw new HumanReadableException(
                "conflicting libraries for key %s: %s != %s", lib.getKey(), lib.getValue(), prev);
          }
        }
      }
    }
    return ImmutableSortedMap.copyOf(libraries);
  }

  /**
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link com.facebook.buck.cxx.NativeLinkable}
   * objects found via the passed in {@link com.facebook.buck.rules.BuildRule} roots.
   *
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      CxxPlatform cxxPlatform, Iterable<? extends BuildRule> inputs, Predicate<Object> traverse)
      throws NoSuchBuildTargetException {
    return getTransitiveSharedLibraries(cxxPlatform, inputs, traverse, x -> false);
  }

  /** @return the {@link NativeLinkTarget} that can be extracted from {@code object}, if any. */
  public static Optional<NativeLinkTarget> getNativeLinkTarget(
      Object object, CxxPlatform cxxPlatform) {
    if (object instanceof NativeLinkTarget) {
      return Optional.of((NativeLinkTarget) object);
    }
    if (object instanceof CanProvideNativeLinkTarget) {
      return ((CanProvideNativeLinkTarget) object).getNativeLinkTarget(cxxPlatform);
    }
    return Optional.empty();
  }
}
