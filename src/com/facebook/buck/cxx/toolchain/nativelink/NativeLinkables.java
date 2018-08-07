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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class NativeLinkables {

  private NativeLinkables() {}

  /**
   * Find {@link NativeLinkable} nodes transitively reachable from the given roots.
   *
   * @param from the starting set of roots to begin the search from.
   * @param passthrough a {@link Function} determining acceptable dependencies to traverse when
   *     searching for {@link NativeLinkable}s.
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkable}.
   */
  public static <T> ImmutableMap<BuildTarget, NativeLinkable> getNativeLinkableRoots(
      Iterable<? extends T> from,
      Function<? super T, Optional<Iterable<? extends T>>> passthrough) {
    ImmutableMap.Builder<BuildTarget, NativeLinkable> nativeLinkables = ImmutableMap.builder();

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
            if (rule instanceof NativeLinkable) {
              NativeLinkable nativeLinkable = (NativeLinkable) rule;
              nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
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
  public static ImmutableList<NativeLinkable> getTopoSortedNativeLinkables(
      Iterable<? extends NativeLinkable> roots,
      Function<? super NativeLinkable, Stream<? extends NativeLinkable>> depsFn) {

    Map<BuildTarget, NativeLinkable> nativeLinkables = new HashMap<>();
    for (NativeLinkable nativeLinkable : roots) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public ImmutableSet<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);

            // Process all the traversable deps.
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            depsFn
                .apply(nativeLinkable)
                .forEach(
                    dep -> {
                      BuildTarget depTarget = dep.getBuildTarget();
                      graph.addEdge(target, depTarget);
                      deps.add(depTarget);
                      nativeLinkables.put(depTarget, dep);
                    });
            return deps.build();
          }
        };
    visitor.start();

    // Topologically sort the rules.
    ImmutableList<BuildTarget> ordered = TopologicalSort.sort(graph).reverse();
    return ordered.stream().map(nativeLinkables::get).collect(ImmutableList.toImmutableList());
  }

  /**
   * @return the first-order dependencies to consider when linking the given {@link NativeLinkable}.
   */
  private static Iterable<? extends NativeLinkable> getDepsForLink(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      NativeLinkable nativeLinkable,
      LinkableDepType linkStyle) {

    // We always traverse a rule's exported native linkables.
    Iterable<? extends NativeLinkable> nativeLinkableDeps =
        nativeLinkable.getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder);

    boolean shouldTraverse;
    switch (nativeLinkable.getPreferredLinkage(cxxPlatform, graphBuilder)) {
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
              nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder));
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
  public static ImmutableList<NativeLinkable> getNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle,
      Predicate<? super NativeLinkable> traverse) {
    return getTopoSortedNativeLinkables(
        inputs,
        nativeLinkable ->
            RichStream.from(getDepsForLink(cxxPlatform, graphBuilder, nativeLinkable, linkStyle))
                .filter(traverse));
  }

  public static ImmutableList<NativeLinkable> getNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle) {
    return getNativeLinkables(cxxPlatform, graphBuilder, inputs, linkStyle, x -> true);
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
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle,
      NativeLinkable nativeLinkable,
      ActionGraphBuilder graphBuilder) {
    NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(cxxPlatform, graphBuilder);
    return nativeLinkable.getNativeLinkableInput(
        cxxPlatform, getLinkStyle(link, linkStyle), graphBuilder);
  }

  /**
   * Collect up and merge all {@link NativeLinkableInput} objects from transitively traversing all
   * unbroken dependency chains of {@link NativeLinkable} objects found via the passed in {@link
   * BuildRule} roots.
   */
  public static <T> NativeLinkableInput getTransitiveNativeLinkableInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends T> inputs,
      Linker.LinkableDepType depType,
      Function<? super T, Optional<Iterable<? extends T>>> passthrough) {

    // Get the topologically sorted native linkables.
    ImmutableMap<BuildTarget, NativeLinkable> roots = getNativeLinkableRoots(inputs, passthrough);
    ImmutableList<NativeLinkable> nativeLinkables =
        getNativeLinkables(cxxPlatform, graphBuilder, roots.values(), depType);
    ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
    for (NativeLinkable nativeLinkable : nativeLinkables) {
      nativeLinkableInputs.add(
          getNativeLinkableInput(cxxPlatform, depType, nativeLinkable, graphBuilder));
    }
    return NativeLinkableInput.concat(nativeLinkableInputs.build());
  }

  public static ImmutableMap<BuildTarget, NativeLinkable> getTransitiveNativeLinkables(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs) {

    Map<BuildTarget, NativeLinkable> nativeLinkables = new HashMap<>();
    for (NativeLinkable nativeLinkable : inputs) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    MutableDirectedGraph<BuildTarget> graph = new MutableDirectedGraph<>();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public Iterable<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkable = Preconditions.checkNotNull(nativeLinkables.get(target));
            graph.addNode(target);
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep :
                Iterables.concat(
                    nativeLinkable.getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
                    nativeLinkable.getNativeLinkableExportedDepsForPlatform(
                        cxxPlatform, graphBuilder))) {
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
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link NativeLinkable} objects found via the
   * passed in {@link BuildRule} roots.
   *
   * @param alwaysIncludeRoots whether to include shared libraries from roots, even if they prefer
   *     static linkage.
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static <T> ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      Iterable<? extends T> inputs,
      Function<? super T, Optional<Iterable<? extends T>>> passthrough,
      boolean alwaysIncludeRoots) {

    ImmutableMap<BuildTarget, NativeLinkable> roots = getNativeLinkableRoots(inputs, passthrough);
    ImmutableMap<BuildTarget, NativeLinkable> nativeLinkables =
        getTransitiveNativeLinkables(cxxPlatform, graphBuilder, roots.values());

    SharedLibrariesBuilder builder = new SharedLibrariesBuilder();
    nativeLinkables
        .entrySet()
        .stream()
        .filter(
            e ->
                e.getValue().getPreferredLinkage(cxxPlatform, graphBuilder)
                        != NativeLinkable.Linkage.STATIC
                    || (alwaysIncludeRoots && roots.containsKey(e.getKey())))
        .forEach(e -> builder.add(cxxPlatform, e.getValue(), graphBuilder));
    return builder.build();
  }

  /** @return the {@link NativeLinkTarget} that can be extracted from {@code object}, if any. */
  public static Optional<NativeLinkTarget> getNativeLinkTarget(
      Object object, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (object instanceof NativeLinkTarget) {
      return Optional.of((NativeLinkTarget) object);
    }
    if (object instanceof CanProvideNativeLinkTarget) {
      return ((CanProvideNativeLinkTarget) object).getNativeLinkTarget(cxxPlatform, graphBuilder);
    }
    return Optional.empty();
  }

  /**
   * Builds a map of shared library names to paths from {@link NativeLinkable}s, throwing a useful
   * error on duplicates.
   */
  public static class SharedLibrariesBuilder {

    private final Map<String, SourcePath> libraries = new LinkedHashMap<>();

    /** Adds libraries from the given {@link NativeLinkable}. */
    public SharedLibrariesBuilder add(
        CxxPlatform cxxPlatform, NativeLinkable linkable, ActionGraphBuilder graphBuilder) {
      ImmutableMap<String, SourcePath> libs =
          linkable.getSharedLibraries(cxxPlatform, graphBuilder);
      for (Map.Entry<String, SourcePath> lib : libs.entrySet()) {
        SourcePath prev = libraries.put(lib.getKey(), lib.getValue());
        if (prev != null && !prev.equals(lib.getValue())) {
          String libTargetString;
          String prevTargetString;
          if ((prev instanceof BuildTargetSourcePath)
              && (lib.getValue() instanceof BuildTargetSourcePath)) {
            libTargetString = ((BuildTargetSourcePath) lib.getValue()).getTarget().toString();
            prevTargetString = ((BuildTargetSourcePath) prev).getTarget().toString();
          } else {
            libTargetString = lib.getValue().toString();
            prevTargetString = prev.toString();
          }
          throw new HumanReadableException(
              "Two libraries in the dependencies have the same output filename: %s\n"
                  + "Those libraries are %s and %s",
              lib.getKey(), libTargetString, prevTargetString);
        }
      }
      return this;
    }

    public ImmutableSortedMap<String, SourcePath> build() {
      return ImmutableSortedMap.copyOf(libraries);
    }
  }
}
