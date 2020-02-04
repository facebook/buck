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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Utility functions for interacting with {@link NativeLinkable} objects. */
public class NativeLinkables {
  private NativeLinkables() {}

  /** @return the nodes found from traversing the given roots in topologically sorted order. */
  public static ImmutableList<? extends NativeLinkable> getTopoSortedNativeLinkables(
      Iterable<? extends NativeLinkable> roots,
      TopologicalSort.Traversable<NativeLinkable> depsFn) {
    // Topologically sort the rules.
    return TopologicalSort.snowflakeSort(
            roots, depsFn, Comparator.comparing(NativeLinkable::getBuildTarget))
        .reverse();
  }

  /**
   * @return the first-order dependencies to consider when linking the given {@link NativeLinkable}.
   */
  private static Iterable<? extends NativeLinkable> getDepsForLink(
      ActionGraphBuilder graphBuilder,
      NativeLinkable nativeLinkable,
      Linker.LinkableDepType linkStyle) {

    // We always traverse a rule's exported native linkables.
    Iterable<? extends NativeLinkable> nativeLinkableDeps =
        nativeLinkable.getNativeLinkableExportedDeps(graphBuilder);

    boolean shouldTraverse;
    switch (nativeLinkable.getPreferredLinkage()) {
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
          Iterables.concat(nativeLinkableDeps, nativeLinkable.getNativeLinkableDeps(graphBuilder));
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
  public static ImmutableList<? extends NativeLinkable> getNativeLinkables(
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle,
      Predicate<? super NativeLinkable> traverse,
      Optional<LinkableListFilter> filter) {
    ImmutableList<? extends NativeLinkable> allLinkables =
        getTopoSortedNativeLinkables(
            inputs,
            nativeLinkable ->
                FluentIterable.from(getDepsForLink(graphBuilder, nativeLinkable, linkStyle))
                    .filter(traverse::test)
                    .iterator());

    if (filter.isPresent()) {
      return filter.get().process(allLinkables, linkStyle);
    }

    return allLinkables;
  }

  /** Extract from the dependency graph all the libraries which must be considered for linking. */
  public static ImmutableList<? extends NativeLinkable> getNativeLinkables(
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle,
      Predicate<? super NativeLinkable> traverse) {
    return getNativeLinkables(graphBuilder, inputs, linkStyle, traverse, Optional.empty());
  }

  /** Extract from the dependency graph all the libraries which must be considered for linking. */
  public static ImmutableList<? extends NativeLinkable> getNativeLinkables(
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle) {
    return getNativeLinkables(graphBuilder, inputs, linkStyle, x -> true);
  }

  /** Extract from the dependency graph all the libraries which must be considered for linking. */
  public static ImmutableList<? extends NativeLinkable> getNativeLinkables(
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> inputs,
      Linker.LinkableDepType linkStyle,
      Optional<LinkableListFilter> filter) {
    return getNativeLinkables(graphBuilder, inputs, linkStyle, x -> true, filter);
  }

  /**
   * Determine the final {@link com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType} given
   * a preferred and requested linkage.
   */
  public static Linker.LinkableDepType getLinkStyle(
      NativeLinkableGroup.Linkage preferredLinkage,
      Linker.LinkableDepType requestedLinkStyle,
      Optional<PicType> picTypeForSharedLinking) {
    Linker.LinkableDepType linkStyle;
    switch (preferredLinkage) {
      case SHARED:
        linkStyle = Linker.LinkableDepType.SHARED;
        break;
      case STATIC:
        linkStyle =
            requestedLinkStyle == Linker.LinkableDepType.STATIC
                    || (picTypeForSharedLinking.isPresent()
                        && picTypeForSharedLinking.get() == PicType.PDC)
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

  /**
   * Determine the final {@link com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType} given
   * a preferred and requested linkage.
   */
  public static Linker.LinkableDepType getLinkStyle(
      NativeLinkableGroup.Linkage preferredLinkage, Linker.LinkableDepType requestedLinkStyle) {
    return getLinkStyle(preferredLinkage, requestedLinkStyle, Optional.empty());
  }

  /** Get the {@link NativeLinkableInput} for a {@link NativeLinkable}. */
  public static NativeLinkableInput getNativeLinkableInput(
      Linker.LinkableDepType linkStyle,
      NativeLinkable nativeLinkable,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    NativeLinkableGroup.Linkage link = nativeLinkable.getPreferredLinkage();
    return nativeLinkable.getNativeLinkableInput(
        getLinkStyle(link, linkStyle), graphBuilder, targetConfiguration);
  }

  /**
   * Collect up and merge all {@link NativeLinkableInput} objects from transitively traversing all
   * unbroken dependency chains of {@link NativeLinkable} objects found via the passed in {@link
   * NativeLinkable} roots.
   */
  public static NativeLinkableInput getTransitiveNativeLinkableInput(
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration,
      Iterable<? extends NativeLinkable> roots,
      Linker.LinkableDepType depType) {
    ImmutableList<? extends NativeLinkable> nativeLinkables =
        getNativeLinkables(graphBuilder, roots, depType);
    ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
    for (NativeLinkable nativeLinkable : nativeLinkables) {
      nativeLinkableInputs.add(
          getNativeLinkableInput(depType, nativeLinkable, graphBuilder, targetConfiguration));
    }
    return NativeLinkableInput.concat(nativeLinkableInputs.build());
  }

  /**
   * Collect up and merge all {@link NativeLinkableInput} objects from transitively traversing all
   * unbroken dependency chains of {@link NativeLinkable} objects found via the passed in {@link
   * NativeLinkable} roots.
   */
  public static ImmutableList<? extends NativeLinkable> getTransitiveNativeLinkables(
      ActionGraphBuilder graphBuilder, Iterable<? extends NativeLinkable> roots) {

    Map<BuildTarget, NativeLinkable> nativeLinkables = new LinkedHashMap<>();
    for (NativeLinkable nativeLinkable : roots) {
      nativeLinkables.put(nativeLinkable.getBuildTarget(), nativeLinkable);
    }

    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(nativeLinkables.keySet()) {
          @Override
          public Iterable<BuildTarget> visit(BuildTarget target) {
            NativeLinkable nativeLinkableGroup =
                Objects.requireNonNull(nativeLinkables.get(target));
            ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
            for (NativeLinkable dep :
                Iterables.concat(
                    nativeLinkableGroup.getNativeLinkableDeps(graphBuilder),
                    nativeLinkableGroup.getNativeLinkableExportedDeps(graphBuilder))) {
              BuildTarget depTarget = dep.getBuildTarget();
              deps.add(depTarget);
              nativeLinkables.put(depTarget, dep);
            }
            return deps.build();
          }
        };
    visitor.start();

    return ImmutableList.copyOf(nativeLinkables.values());
  }

  /**
   * Collect all the shared libraries generated by {@link NativeLinkable}s found by transitively
   * traversing all unbroken dependency chains of {@link NativeLinkable} objects found via the
   * passed in {@link NativeLinkable} roots.
   *
   * @param alwaysIncludeRoots whether to include shared libraries from roots, even if they prefer
   *     static linkage.
   * @return a mapping of library name to the library {@link SourcePath}.
   */
  public static ImmutableSortedMap<String, SourcePath> getTransitiveSharedLibraries(
      ActionGraphBuilder graphBuilder,
      Iterable<? extends NativeLinkable> roots,
      boolean alwaysIncludeRoots) {
    ImmutableSet<BuildTarget> rootTargets =
        RichStream.from(roots).map(l -> l.getBuildTarget()).toImmutableSet();

    ImmutableList<? extends NativeLinkable> nativeLinkables =
        getTransitiveNativeLinkables(graphBuilder, roots);

    SharedLibrariesBuilder builder = new SharedLibrariesBuilder();
    builder.addAll(
        graphBuilder,
        nativeLinkables.stream()
            .filter(
                e ->
                    e.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC
                        || (alwaysIncludeRoots && rootTargets.contains(e.getBuildTarget())))
            .collect(Collectors.toList()));
    return builder.build();
  }

  /**
   * Builds a map of shared library names to paths from {@link NativeLinkable}s, throwing a useful
   * error on duplicates.
   */
  public static class SharedLibrariesBuilder {

    private final Map<String, SourcePath> libraries = new LinkedHashMap<>();

    private SharedLibrariesBuilder add(ImmutableMap<String, SourcePath> libs) {
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

    /** Adds libraries from the given {@link NativeLinkableGroup}s, potentially in parallel. */
    public SharedLibrariesBuilder addAll(
        ActionGraphBuilder graphBuilder, Collection<NativeLinkable> linkables) {
      graphBuilder
          .getParallelizer()
          .maybeParallelizeTransform(
              linkables, nativeLinkable -> nativeLinkable.getSharedLibraries(graphBuilder))
          .forEach(this::add);
      return this;
    }

    public ImmutableSortedMap<String, SourcePath> build() {
      return ImmutableSortedMap.copyOf(libraries);
    }
  }
}
