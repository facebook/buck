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
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.PrebuiltCxxLibrary;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Helper for AndroidLibraryGraphEnhancer to handle semi-transparent merging of native libraries.
 * <p>
 * Older versions of Android have a limit on how many DSOs they can load into one process.
 * To work around this limit, it can be helpful to merge multiple libraries together
 * based on a per-app configuration.  This enhancer replaces the raw NativeLinkable rules
 * with versions that merge multiple logical libraries into one physical library.
 * We also generate code to allow the merge results to be queried at runtime.
 * <p>
 * Note that when building an app that uses merged libraries, we need to adjust the way we
 * link *all* libraries, because their DT_NEEDED can change even if they aren't being merged
 * themselves.  Future work could identify cases where the original build rules are sufficient.
 */
class NativeLibraryMergeEnhancer {
  private NativeLibraryMergeEnhancer() {
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  static NativeLibraryMergeEnhancementResult enhance(
      CxxBuckConfig cxxBuckConfig,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildRuleParams buildRuleParams,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      Map<String, List<Pattern>> mergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      ImmutableMultimap<APKModule, NativeLinkable> linkables,
      ImmutableMultimap<APKModule, NativeLinkable> linkablesAssets)
      throws NoSuchBuildTargetException {

    NativeLibraryMergeEnhancementResult.Builder builder =
        NativeLibraryMergeEnhancementResult.builder();


    ImmutableSet<APKModule> modules = ImmutableSet.<APKModule>builder()
        .addAll(linkables.keySet())
        .addAll(linkablesAssets.keySet())
        .build();

    ImmutableSortedMap.Builder<String, String> sonameMapBuilder = ImmutableSortedMap.naturalOrder();

    for (APKModule module : modules) {
      // Sort by build target here to ensure consistent behavior.
      Iterable<NativeLinkable> allLinkables = FluentIterable.from(
          Iterables.concat(linkables.get(module), linkablesAssets.get(module)))
          .toSortedList(HasBuildTarget.BUILD_TARGET_COMPARATOR);

      final ImmutableSet<NativeLinkable> linkableAssetSet =
          ImmutableSet.copyOf(linkablesAssets.get(module));
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership =
          makeConstituentMap(
              buildRuleParams,
              mergeMap,
              allLinkables,
              linkableAssetSet);

      sonameMapBuilder.putAll(makeSonameMap(
          // sonames can *theoretically* differ per-platform, but right now they don't on Android,
          // so just pick the first platform and use that to get all the sonames.
          nativePlatforms.values().iterator().next().getCxxPlatform(),
          linkableMembership));

      Iterable<MergedNativeLibraryConstituents> orderedConstituents = getOrderedMergedConstituents(
          buildRuleParams,
          linkableMembership);

      Optional<NativeLinkable> glueLinkable = Optional.empty();
      if (nativeLibraryMergeGlue.isPresent()) {
        BuildRule rule = ruleResolver.getRule(nativeLibraryMergeGlue.get());
        if (!(rule instanceof NativeLinkable)) {
          throw new RuntimeException(
              "Native library merge glue " + rule.getBuildTarget() +
                  " for application " + buildRuleParams.getBuildTarget() + " is not linkable.");
        }
        glueLinkable = Optional.of(((NativeLinkable) rule));
      }

      Set<MergedLibNativeLinkable> mergedLinkables = createLinkables(
          cxxBuckConfig,
          ruleResolver,
          pathResolver,
          buildRuleParams,
          glueLinkable,
          orderedConstituents);

      for (MergedLibNativeLinkable linkable : mergedLinkables) {
        if (Collections.disjoint(linkable.constituents.getLinkables(), linkableAssetSet)) {
          builder.putMergedLinkables(module, linkable);
        } else if (linkableAssetSet.containsAll(linkable.constituents.getLinkables())) {
          builder.putMergedLinkablesAssets(module, linkable);
        }
      }
    }
    builder.setSonameMapping(sonameMapBuilder.build());
    return builder.build();
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

  private static ImmutableSortedMap<String, String> makeSonameMap(
      CxxPlatform anyAndroidCxxPlatform,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership)
      throws NoSuchBuildTargetException {
    ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();

    for (Map.Entry<NativeLinkable, MergedNativeLibraryConstituents> entry :
        linkableMembership.entrySet()) {
      if (!entry.getValue().getSoname().isPresent()) {
        continue;
      }
      String mergedName = entry.getValue().getSoname().get();
      for (String origName : entry.getKey().getSharedLibraries(anyAndroidCxxPlatform).keySet()) {
        builder.put(origName, mergedName);
      }
    }

    return builder.build();
  }

  /**
   * Topo-sort the constituents objects so we can process deps first.
   */
  private static Iterable<MergedNativeLibraryConstituents> getOrderedMergedConstituents(
      BuildRuleParams buildRuleParams,
      final Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership) {
    MutableDirectedGraph<MergedNativeLibraryConstituents> graph = new MutableDirectedGraph<>();
    for (MergedNativeLibraryConstituents constituents : linkableMembership.values()) {
      graph.addNode(constituents);
      for (NativeLinkable constituentLinkable : constituents.getLinkables()) {
        // For each dep of each constituent of each merged lib...
        for (NativeLinkable dep : Iterables.concat(
            constituentLinkable.getNativeLinkableDeps(),
            constituentLinkable.getNativeLinkableExportedDeps())) {
          // If that dep is in a different merged lib, add a dependency.
          MergedNativeLibraryConstituents mergedDep =
              Preconditions.checkNotNull(linkableMembership.get(dep));
          if (mergedDep != constituents) {
            graph.addEdge(constituents, mergedDep);
          }
        }
      }
    }

    // Check for cycles in the merged dependency graph.
    // If any are found, spent a lot of effort building an error message
    // that actually shows the dependency cycle.
    for (ImmutableSet<MergedNativeLibraryConstituents> fullCycle : graph.findCycles()) {
      HashSet<MergedNativeLibraryConstituents> partialCycle = new LinkedHashSet<>();
      MergedNativeLibraryConstituents item = fullCycle.iterator().next();
      while (true) {
        if (partialCycle.contains(item)) {
          break;
        }
        partialCycle.add(item);
        item = Sets.intersection(
            ImmutableSet.copyOf(graph.getOutgoingNodesFor(item)),
            fullCycle
        ).iterator().next();
      }

      StringBuilder cycleString = new StringBuilder().append("[ ");
      boolean foundStart = false;
      for (MergedNativeLibraryConstituents member : partialCycle) {
        if (member == item) {
          foundStart = true;
        }
        if (foundStart) {
          cycleString.append(member);
          cycleString.append(" -> ");
        }
      }
      cycleString.append(item);
      cycleString.append(" ]");
      throw new RuntimeException(
          "Dependency cycle detected when merging native libs for " +
              buildRuleParams.getBuildTarget() +
              ": " + cycleString);
    }

    return TopologicalSort.sort(graph, x -> true);
  }

  /**
   * Create the final Linkables that will be passed to the later stages of graph enhancement.
   */
  private static Set<MergedLibNativeLinkable> createLinkables(
      CxxBuckConfig cxxBuckConfig,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildRuleParams buildRuleParams,
      Optional<NativeLinkable> glueLinkable,
      Iterable<MergedNativeLibraryConstituents> orderedConstituents) {
    // Map from original linkables to the Linkables they have been merged into.
    final Map<NativeLinkable, MergedLibNativeLinkable> mergeResults = new HashMap<>();

    for (MergedNativeLibraryConstituents constituents : orderedConstituents) {
      final ImmutableCollection<NativeLinkable> preMergeLibs = constituents.getLinkables();

      List<MergedLibNativeLinkable> orderedDeps = getStructuralDeps(
          constituents,
          NativeLinkable::getNativeLinkableDeps,
          mergeResults);
      List<MergedLibNativeLinkable> orderedExportedDeps = getStructuralDeps(
          constituents,
          NativeLinkable::getNativeLinkableExportedDeps,
          mergeResults);

      MergedLibNativeLinkable mergedLinkable = new MergedLibNativeLinkable(
          cxxBuckConfig,
          ruleResolver,
          pathResolver,
          buildRuleParams,
          constituents,
          orderedDeps,
          orderedExportedDeps,
          glueLinkable);

      for (NativeLinkable lib : preMergeLibs) {
        // Track what was merged into this so later linkables can find us as a dependency.
        mergeResults.put(lib, mergedLinkable);
      }
    }

    return ImmutableSortedSet.copyOf(HasBuildTarget.BUILD_TARGET_COMPARATOR, mergeResults.values());
  }

  /**
   * Get the merged version of all deps, across all platforms.
   *
   * @param depType Function that returns the proper dep type: exported or not.
   */
  private static List<MergedLibNativeLinkable> getStructuralDeps(
      MergedNativeLibraryConstituents constituents,
      Function<NativeLinkable, Iterable<? extends NativeLinkable>> depType,
      Map<NativeLinkable, MergedLibNativeLinkable> alreadyMerged) {
    // Using IdentityHashMap as a hash set.
    Map<MergedLibNativeLinkable, Void> structuralDeps = new HashMap<>();
    for (NativeLinkable linkable : constituents.getLinkables()) {
      for (NativeLinkable dep : depType.apply(linkable)) {
        MergedLibNativeLinkable mappedDep = alreadyMerged.get(dep);
        if (mappedDep == null) {
          if (constituents.getLinkables().contains(dep)) {
            // We're depending on one of our other constituents.  We can drop this.
            continue;
          }
          throw new RuntimeException(
              "Can't find mapped dep of " + dep + " for " + linkable + ".  This is a bug.");
        }
        structuralDeps.put(mappedDep, null);
      }
    }
    // Sort here to ensure consistent ordering, because the build target depends on the order.
    return Ordering.from(HasBuildTarget.BUILD_TARGET_COMPARATOR)
        .sortedCopy(structuralDeps.keySet());
  }

  /**
   * Data object for internal use, representing the source libraries getting merged together
   * into one DSO.  Libraries not being merged will have one linkable and no soname.
   */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractMergedNativeLibraryConstituents
      implements Comparable<AbstractMergedNativeLibraryConstituents> {
    public abstract Optional<String> getSoname();

    public abstract ImmutableSet<NativeLinkable> getLinkables();

    @Value.Check
    protected void check() {
      // Soname can only be absent for a constituency of a single un-merged lib.
      if (!getSoname().isPresent()) {
        Preconditions.checkArgument(getLinkables().size() == 1);
      }
    }

    @Override
    public String toString() {
      if (getSoname().isPresent()) {
        return "merge:" + getSoname().get();
      }
      return "no-merge:" + getLinkables().iterator().next().getBuildTarget();
    }

    @Override
    public int compareTo(AbstractMergedNativeLibraryConstituents other) {
      return toString().compareTo(other.toString());
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractNativeLibraryMergeEnhancementResult {
    public abstract ImmutableMultimap<APKModule, NativeLinkable> getMergedLinkables();

    public abstract ImmutableMultimap<APKModule, NativeLinkable> getMergedLinkablesAssets();

    public abstract ImmutableSortedMap<String, String> getSonameMapping();
  }

  /**
   * Our own implementation of NativeLinkable, which is consumed by
   * later phases of graph enhancement.  It represents a single merged library.
   */
  private static class MergedLibNativeLinkable implements NativeLinkable {
    private final CxxBuckConfig cxxBuckConfig;
    private final BuildRuleResolver ruleResolver;
    private final SourcePathResolver pathResolver;
    private final BuildRuleParams baseBuildRuleParams;
    private final MergedNativeLibraryConstituents constituents;
    private final Optional<NativeLinkable> glueLinkable;
    private final Map<NativeLinkable, MergedLibNativeLinkable> mergedDepMap;
    private final BuildTarget buildTarget;
    private final boolean canUseOriginal;
    // Note: update constructBuildTarget whenever updating new fields.

    MergedLibNativeLinkable(
        CxxBuckConfig cxxBuckConfig,
        BuildRuleResolver ruleResolver,
        SourcePathResolver pathResolver,
        BuildRuleParams baseBuildRuleParams,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable) {
      this.cxxBuckConfig = cxxBuckConfig;
      this.ruleResolver = ruleResolver;
      this.pathResolver = pathResolver;
      this.baseBuildRuleParams = baseBuildRuleParams;
      this.constituents = constituents;
      this.glueLinkable = glueLinkable;

      Iterable<MergedLibNativeLinkable> allDeps =
          Iterables.concat(orderedDeps, orderedExportedDeps);
      Map<NativeLinkable, MergedLibNativeLinkable> mergedDeps = new HashMap<>();
      for (MergedLibNativeLinkable dep : allDeps) {
        for (NativeLinkable linkable : dep.constituents.getLinkables()) {
          MergedLibNativeLinkable old = mergedDeps.put(linkable, dep);
          if (old != null && old != dep) {
            throw new RuntimeException(String.format(
                "BUG: When processing %s, dep %s mapped to both %s and %s",
                constituents, linkable, dep, old));
          }
        }
      }
      mergedDepMap = Collections.unmodifiableMap(mergedDeps);

      canUseOriginal = computeCanUseOriginal(constituents, allDeps);

      buildTarget = constructBuildTarget(
          baseBuildRuleParams,
          constituents,
          orderedDeps,
          orderedExportedDeps,
          glueLinkable);
    }

    /**
     * If a library is not involved in merging, and neither are any of its transitive deps,
     * we can use just the original shared object, which lets us share cache with
     * apps that don't use merged libraries at all.
     */
    private static boolean computeCanUseOriginal(
        MergedNativeLibraryConstituents constituents,
        Iterable<MergedLibNativeLinkable> allDeps) {
      if (constituents.getSoname().isPresent()) {
        return false;
      }

      for (MergedLibNativeLinkable dep : allDeps) {
        if (!dep.canUseOriginal) {
          return false;
        }
      }
      return true;
    }

    @Override
    public String toString() {
      return "MergedLibNativeLinkable<" + buildTarget + ">";
    }

    // TODO(dreiss): Maybe cache this and other methods?  Would have to be per-platform.
    String getSoname(CxxPlatform platform) throws NoSuchBuildTargetException {
      if (constituents.getSoname().isPresent()) {
        return constituents.getSoname().get();
      }
      ImmutableMap<String, SourcePath> shared =
          constituents.getLinkables().iterator().next().getSharedLibraries(platform);
      Preconditions.checkState(shared.size() == 1);
      return shared.keySet().iterator().next();
    }

    @Override
    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    private static BuildTarget constructBuildTarget(
        BuildRuleParams baseBuildRuleParams,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable) {
      BuildTarget initialTarget;
      if (!constituents.getSoname().isPresent()) {
        // No soname means this is library isn't really merged.
        // We use its constituent as the base target to ensure that
        // it is shared between all apps with the same merge structure.
        initialTarget = constituents.getLinkables().iterator().next().getBuildTarget();
      } else {
        // If we're merging, construct a base target in the app's directory.
        // This ensure that all apps in this directory will
        // have a chance to share the target.
        BuildTarget baseBuildTarget = baseBuildRuleParams.getBuildTarget();
        UnflavoredBuildTarget baseUnflavored = baseBuildTarget.getUnflavoredBuildTarget();
        UnflavoredBuildTarget unflavored =
            UnflavoredBuildTarget.builder()
                .from(baseUnflavored)
                .setShortName(
                    "merged_lib_" + Flavor.replaceInvalidCharacters(constituents.getSoname().get()))
                .build();
        initialTarget = BuildTarget.of(unflavored);
      }

      // Two merged libs (for different apps) can have the same constituents,
      // but they still need to be separate rules if their dependencies differ.
      // However, we want to share if possible to share cache artifacts.
      // Therefore, transitively hash the dependencies' targets
      // to create a unique string to add to our target.
      Hasher hasher = Hashing.murmur3_32().newHasher();
      for (NativeLinkable nativeLinkable : constituents.getLinkables()) {
        hasher.putString(nativeLinkable.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }
      // Hash all the merged deps, in order.
      hasher.putString("__DEPS__^", Charsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }
      // Separate exported deps.  This doesn't affect linking, but it can affect our dependents
      // if we're building two apps at once.
      hasher.putString("__EXPORT__^", Charsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedExportedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }

      // Glue can vary per-app, so include that in the hash as well.
      if (glueLinkable.isPresent()) {
        hasher.putString("__GLUE__^", Charsets.UTF_8);
        hasher.putString(glueLinkable.get().getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }

      String mergeFlavor = "merge_structure_" + hasher.hash();

      return BuildTarget.builder().from(initialTarget)
          .addFlavors(ImmutableFlavor.of(mergeFlavor))
          .build();
    }

    private BuildTarget getBuildTargetForPlatform(CxxPlatform cxxPlatform) {
      return BuildTarget.builder().from(getBuildTarget())
          .addFlavors(cxxPlatform.getFlavor())
          .build();
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
      return getMappedDeps(NativeLinkable::getNativeLinkableDeps);
    }

    @Override
    public Iterable<? extends NativeLinkable>
    getNativeLinkableExportedDeps() {
      return getMappedDeps(NativeLinkable::getNativeLinkableExportedDeps);
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDepsForPlatform(
        final CxxPlatform cxxPlatform) {
      return getMappedDeps(l -> l.getNativeLinkableDepsForPlatform(cxxPlatform));
    }

    @Override
    public Iterable<? extends NativeLinkable>
    getNativeLinkableExportedDepsForPlatform(final CxxPlatform cxxPlatform) {
      return getMappedDeps(l -> l.getNativeLinkableExportedDepsForPlatform(cxxPlatform));
    }

    private Iterable<? extends NativeLinkable> getMappedDeps(
        Function<NativeLinkable, Iterable<? extends NativeLinkable>> depType) {
      ImmutableList.Builder<NativeLinkable> builder = ImmutableList.builder();

      for (NativeLinkable linkable : constituents.getLinkables()) {
        for (NativeLinkable dep : depType.apply(linkable)) {
          // Don't try to depend on ourselves.
          if (!constituents.getLinkables().contains(dep)) {
            builder.add(mergedDepMap.get(dep));
          }
        }
      }

      return builder.build();
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        final CxxPlatform cxxPlatform,
        final Linker.LinkableDepType type) throws NoSuchBuildTargetException {

      // This path gets taken for a force-static library.
      if (type == Linker.LinkableDepType.STATIC_PIC) {
        ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
        for (NativeLinkable linkable : constituents.getLinkables()) {
          builder.add(
              linkable.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC_PIC));
        }
        return NativeLinkableInput.concat(builder.build());
      }

      // STATIC isn't valid because we always need PIC on Android.
      Preconditions.checkArgument(type == Linker.LinkableDepType.SHARED);

      ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
      // TODO(dreiss): Should we cache the output of getSharedLibraries per-platform?
      ImmutableMap<String, SourcePath> sharedLibraries = getSharedLibraries(cxxPlatform);
      for (SourcePath sharedLib : sharedLibraries.values()) {
        // If we have a shared library, our dependents should link against it.
        // Might be multiple shared libraries if prebuilts are included.
        argsBuilder.add(new SourcePathArg(pathResolver, sharedLib));
      }

      // If our constituents have exported linker flags, our dependents should use them.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable instanceof CxxLibrary) {
          argsBuilder.addAll(((CxxLibrary) linkable).getExportedLinkerFlags(cxxPlatform));
        } else if (linkable instanceof PrebuiltCxxLibrary) {
          argsBuilder.addAll(
              StringArg.from(((PrebuiltCxxLibrary) linkable).getExportedLinkerFlags(cxxPlatform)));
        }
      }

      return NativeLinkableInput.of(
          argsBuilder.build(),
          ImmutableList.of(),
          ImmutableList.of());
    }

    private NativeLinkableInput getImmediateNativeLinkableInput(CxxPlatform cxxPlatform)
        throws NoSuchBuildTargetException {
      final Linker linker = cxxPlatform.getLd().resolve(ruleResolver);
      ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
      ImmutableList<NativeLinkable> usingGlue = ImmutableList.of();
      if (glueLinkable.isPresent() && constituents.getSoname().isPresent()) {
        usingGlue = ImmutableList.of(glueLinkable.get());
      }

      for (NativeLinkable linkable : Iterables.concat(usingGlue, constituents.getLinkables())) {
        if (linkable instanceof NativeLinkTarget) {
          // If this constituent is a NativeLinkTarget, use its input to get raw objects and
          // linker flags.
          builder.add(
              ((NativeLinkTarget) linkable).getNativeLinkTargetInput(cxxPlatform));
        } else {
          // Otherwise, just get the static pic output.
          NativeLinkableInput staticPic =
              linkable.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC_PIC);
          builder.add(
              staticPic.withArgs(
                  FluentIterable.from(staticPic.getArgs())
                      .transformAndConcat(linker::linkWhole)));
        }
      }
      return NativeLinkableInput.concat(builder.build());
    }

    @Override
    public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      // If we have any non-static constituents, our preferred linkage is shared
      // (because stuff in Android is shared by default).  That's the common case.
      // If *all* of our constituents are force_static=True, we will also be preferred static.
      // Most commonly, that will happen when we're just wrapping a single force_static constituent.
      // It's also possible that multiple force_static libs could be merged,
      // but that has no effect.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable.getPreferredLinkage(cxxPlatform) != Linkage.STATIC) {
          return Linkage.SHARED;
        }
      }

      return Linkage.STATIC;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
        throws NoSuchBuildTargetException {
      if (getPreferredLinkage(cxxPlatform) == Linkage.STATIC) {
        return ImmutableMap.of();
      }

      ImmutableMap<String, SourcePath> originalSharedLibraries =
          constituents.getLinkables().iterator().next().getSharedLibraries(cxxPlatform);
      if (canUseOriginal || originalSharedLibraries.isEmpty()) {
        return originalSharedLibraries;
      }

      String soname = getSoname(cxxPlatform);
      BuildTarget target = getBuildTargetForPlatform(cxxPlatform);
      Optional<BuildRule> ruleOptional = ruleResolver.getRuleOptional(target);
      if (!ruleOptional.isPresent()) {
        CxxLink rule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            baseBuildRuleParams,
            ruleResolver,
            pathResolver,
            target,
            Linker.LinkType.SHARED,
            Optional.of(soname),
            BuildTargets.getGenPath(
                baseBuildRuleParams.getProjectFilesystem(),
                target,
                "%s/" + getSoname(cxxPlatform)),
            // Android Binaries will use share deps by default.
            Linker.LinkableDepType.SHARED,
            Iterables.concat(
                getNativeLinkableDepsForPlatform(cxxPlatform),
                getNativeLinkableExportedDepsForPlatform(cxxPlatform)),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            getImmediateNativeLinkableInput(cxxPlatform));
        ruleResolver.addToIndex(rule);
      }
      return ImmutableMap.of(
          soname,
          new BuildTargetSourcePath(target)
      );
    }
  }
}
