/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Helper for AndroidLibraryGraphEnhancer to handle semi-transparent merging of native libraries.
 *
 * Older versions of Android have a limit on how many DSOs they can load into one process.
 * To work around this limit, it can be helpful to merge multiple libraries together
 * based on a per-app configuration.  This enhancer replaces the raw NativeLinkable rules
 * with versions that merge multiple logical libraries into one physical library.
 * We also generate code to allow the merge results to be queried at runtime.
 */
class NativeLibraryMergeEnhancer {
  private NativeLibraryMergeEnhancer() {}

  static NativeLibraryMergeEnhancementResult enhance(
      CxxBuckConfig cxxBuckConfig,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildRuleParams buildRuleParams,
      Map<String, List<Pattern>> mergeMap,
      ImmutableList<NativeLinkable> linkables,
      ImmutableList<NativeLinkable> linkablesAssets) {
    // Suppress warnings.
    cxxBuckConfig.getClass();
    ruleResolver.getClass();
    pathResolver.getClass();

    // Sort by build target here to ensure consistent behavior.
    Iterable<NativeLinkable> allLinkables = FluentIterable.from(
        Iterables.concat(linkables, linkablesAssets))
        .toSortedList(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    ImmutableSet<NativeLinkable> linkableAssetSet = ImmutableSet.copyOf(linkablesAssets);
    Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership =
        makeConstituentMap(
            buildRuleParams,
            mergeMap,
            allLinkables,
            linkableAssetSet);

    Iterable<MergedNativeLibraryConstituents> orderedConstituents = getOrderedMergedConstituents(
        buildRuleParams,
        linkableMembership);


    orderedConstituents.getClass();


    return NativeLibraryMergeEnhancementResult.builder()
        .addAllMergedLinkables(linkables)
        .addAllMergedLinkablesAssets(linkablesAssets)
        .build();
  }

  private static Map<NativeLinkable, MergedNativeLibraryConstituents>
  makeConstituentMap(
      BuildRuleParams buildRuleParams,
      Map<String, List<Pattern>> mergeMap,
      Iterable<NativeLinkable> allLinkables,
      ImmutableSet<NativeLinkable> linkableAssetSet) {
    List<MergedNativeLibraryConstituents> allConstituents = new ArrayList<>();

    for (Map.Entry<String, List<Pattern>> mergeConfigEntry : mergeMap.entrySet()) {
      String mergeSoname = mergeConfigEntry.getKey();
      List<Pattern> patterns = mergeConfigEntry.getValue();

      MergedNativeLibraryConstituents.Builder constituentsBuilder =
          MergedNativeLibraryConstituents.builder()
              .setSoname(mergeSoname);

      for (Pattern pattern : patterns) {
        for (NativeLinkable linkable : allLinkables) {
          // TODO(dreiss): Might be a good idea to cache .getBuildTarget().toString().
          if (pattern.matcher(linkable.getBuildTarget().toString()).find()) {
            constituentsBuilder.addLinkables(linkable);
          }
        }
      }

      allConstituents.add(constituentsBuilder.build());
    }

    Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership = new HashMap<>();
    for (MergedNativeLibraryConstituents constituents : allConstituents) {
      boolean hasNonAssets = false;
      boolean hasAssets = false;

      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkableMembership.containsKey(linkable)) {
          throw new RuntimeException(String.format(
              "When processing %s, attempted to merge %s into both %s and %s",
              buildRuleParams.getBuildTarget(),
              linkable,
              linkableMembership.get(linkable),
              constituents));
        }
        linkableMembership.put(linkable, constituents);

        if (linkableAssetSet.contains(linkable)) {
          hasAssets = true;
        } else {
          hasNonAssets = true;
        }

      }
      if (hasAssets && hasNonAssets) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "When processing %s, merged lib '%s' contains both asset and non-asset libraries.\n",
            buildRuleParams.getBuildTarget(), constituents));
        for (NativeLinkable linkable : constituents.getLinkables()) {
          sb.append(String.format(
              "  %s -> %s\n",
              linkable,
              linkableAssetSet.contains(linkable) ? "asset" : "not asset"));
        }
        throw new RuntimeException(sb.toString());
      }
    }

    for (NativeLinkable linkable : allLinkables) {
      if (!linkableMembership.containsKey(linkable)) {
        linkableMembership.put(
            linkable,
            MergedNativeLibraryConstituents.builder()
                .addLinkables(linkable)
                .build());
      }
    }
    return linkableMembership;
  }

  /**
   * Topo-sort the constituents objects so we can process deps first.
   */
  private static Iterable<MergedNativeLibraryConstituents> getOrderedMergedConstituents(
      BuildRuleParams buildRuleParams,
      final Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership) {

    // Merged libs that are not depended on by any other merged lib
    // will be the roots of our traversal.
    // Start with all merged libs as possible roots.
    HashSet<MergedNativeLibraryConstituents> possibleRoots =
        new HashSet<>(linkableMembership.values());

    for (Map.Entry<NativeLinkable, MergedNativeLibraryConstituents> entry :
        linkableMembership.entrySet()) {
      for (NativeLinkable constituentLinkable : entry.getValue().getLinkables()) {
        // For each dep of each constituent of each merged lib...
        for (NativeLinkable dep : constituentLinkable.getNativeLinkableDeps(null)) {
          // If that dep is in a different merged lib, the latter is not a root.
          MergedNativeLibraryConstituents mergedDep = linkableMembership.get(dep);
          if (mergedDep != entry.getValue()) {
            possibleRoots.remove(mergedDep);
          }
        }
      }
    }

    Iterable<MergedNativeLibraryConstituents> ordered;
    try {
      ordered = new AcyclicDepthFirstPostOrderTraversal<>(
          new GraphTraversable<MergedNativeLibraryConstituents>() {
            @Override
            public Iterator<MergedNativeLibraryConstituents> findChildren(
                MergedNativeLibraryConstituents node) {
              ImmutableSet.Builder<MergedNativeLibraryConstituents> depBuilder =
                  ImmutableSet.builder();
              for (NativeLinkable linkable : node.getLinkables()) {
                // Ideally, we would sort the deps to get consistent traversal order,
                // but in practice they are already SortedSets, so it's not necessary.
                for (NativeLinkable dep : Iterables.concat(
                    linkable.getNativeLinkableDeps(null),
                    linkable.getNativeLinkableExportedDeps(null))) {
                  MergedNativeLibraryConstituents mappedDep =
                      linkableMembership.get(dep);
                  // Merging can result in a native library "depending on itself",
                  // which is a cycle.  Just drop these unnecessary deps.
                  if (mappedDep != node) {
                    depBuilder.add(mappedDep);
                  }
                }
              }
              return depBuilder.build().iterator();
            }
          }).traverse(possibleRoots);
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new RuntimeException(
          "Dependency cycle detected when merging native libs for " +
              buildRuleParams.getBuildTarget(),
          e);
    }
    return ordered;
  }


  /**
   * Data object for internal use, representing the source libraries getting merged together
   * into one DSO.  Libraries not being merged will have one linkable and no soname.
   */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractMergedNativeLibraryConstituents {
    public abstract Optional<String> getSoname();
    public abstract ImmutableSet<NativeLinkable> getLinkables();

    @Override
    public String toString() {
      if (getSoname().isPresent()) {
        return "merge:" + getSoname().get();
      }
      return "no-merge:" + getLinkables().iterator().next().getBuildTarget();
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractNativeLibraryMergeEnhancementResult {
    public abstract ImmutableList<NativeLinkable> getMergedLinkables();
    public abstract ImmutableList<NativeLinkable> getMergedLinkablesAssets();
  }
}
