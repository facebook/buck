/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.packageable.NativeLinkableEnhancementResult;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.LinkOutputPostprocessor;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSymbolTable;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Comparators;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * Helper for AndroidLibraryGraphEnhancer to handle semi-transparent merging of native libraries.
 *
 * <p>Older versions of Android have a limit on how many DSOs they can load into one process. To
 * work around this limit, it can be helpful to merge multiple libraries together based on a per-app
 * configuration. This enhancer replaces the raw NativeLinkable rules with versions that merge
 * multiple logical libraries into one physical library. We also generate code to allow the merge
 * results to be queried at runtime.
 *
 * <p>Note that when building an app that uses merged libraries, we need to adjust the way we link
 * *all* libraries, because their DT_NEEDED can change even if they aren't being merged themselves.
 * Future work could identify cases where the original build rules are sufficient.
 */
class NativeLibraryMergeEnhancer {
  private NativeLibraryMergeEnhancer() {}

  @SuppressWarnings("PMD.PrematureDeclaration")
  public static NativeLibraryMergeEnhancementResult enhance(
      CellPathResolver cellPathResolver,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      ActionGraphBuilder graphBuilder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      Optional<ImmutableMap<String, ImmutableList<Pattern>>> mergeMap,
      Optional<ImmutableList<Pair<String, ImmutableList<Pattern>>>> mergeSequence,
      Optional<ImmutableList<Pattern>> mergeSequenceBlocklist,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      ImmutableMap<TargetCpuType, NativeLinkableEnhancementResult> nativeLinkables) {

    ImmutableMap.Builder<TargetCpuType, NativeLinkableEnhancementResult> mergedLinkablesBuilder =
        ImmutableMap.builder();

    ImmutableSet<TargetCpuType> platforms = nativeLinkables.keySet();

    Map<String, SonameMergeData> sonameMapBuilder = new HashMap<>();
    ImmutableSetMultimap.Builder<String, String> sonameTargetsBuilder =
        ImmutableSetMultimap.builder();

    SonameMapBuilder mapBuilder =
        (isActuallyMerged,
            originalName,
            mergedName,
            targetName,
            includeInAndroidMergeMapOutput) -> {
          if (isActuallyMerged) {
            sonameMapBuilder.put(
                originalName,
                ImmutableSonameMergeData.ofImpl(mergedName, includeInAndroidMergeMapOutput));
          }
          if (targetName.isPresent()) {
            String actualName = isActuallyMerged ? mergedName : originalName;
            sonameTargetsBuilder.put(actualName, targetName.get());
          }
        };

    for (TargetCpuType cpuType : platforms) {
      NativeLinkableEnhancementResult baseResult = nativeLinkables.get(cpuType);
      CxxPlatform cxxPlatform = nativePlatforms.get(cpuType).getCxxPlatform();

      Multimap<APKModule, NativeLinkable> linkables = baseResult.getNativeLinkables();
      Multimap<APKModule, NativeLinkable> assetLinkables = baseResult.getNativeLinkableAssets();

      ImmutableSet<APKModule> modules =
          ImmutableSet.<APKModule>builder()
              .addAll(linkables.keySet())
              .addAll(assetLinkables.keySet())
              .build();

      Stream<? extends NativeLinkable> allModulesLinkables = Stream.empty();
      ImmutableMap.Builder<NativeLinkable, APKModule> linkableAssetMapBuilder =
          ImmutableMap.builder();
      for (APKModule module : modules) {
        allModulesLinkables = Stream.concat(allModulesLinkables, linkables.get(module).stream());
        allModulesLinkables =
            Stream.concat(allModulesLinkables, assetLinkables.get(module).stream());
        assetLinkables.get(module).stream()
            .forEach(assetLinkable -> linkableAssetMapBuilder.put(assetLinkable, module));
      }

      // Sort by build target here to ensure consistent behavior.
      Iterable<NativeLinkable> allLinkables =
          allModulesLinkables
              .sorted(Comparator.comparing(NativeLinkable::getBuildTarget))
              .collect(ImmutableList.toImmutableList());

      ImmutableMap<NativeLinkable, APKModule> linkableAssetMap = linkableAssetMapBuilder.build();
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership =
          makeConstituentMap(
              buildTarget,
              mergeMap,
              mergeSequence,
              mergeSequenceBlocklist,
              graphBuilder,
              allLinkables,
              linkableAssetMap);

      Iterable<MergedNativeLibraryConstituents> orderedConstituents =
          getOrderedMergedConstituents(buildTarget, graphBuilder, linkableMembership);

      Optional<NativeLinkable> glueLinkable = Optional.empty();
      if (nativeLibraryMergeGlue.isPresent()) {
        BuildRule rule = graphBuilder.getRule(nativeLibraryMergeGlue.get());
        if (!(rule instanceof NativeLinkableGroup)) {
          throw new RuntimeException(
              "Native library merge glue "
                  + rule.getBuildTarget()
                  + " for application "
                  + buildTarget
                  + " is not linkable.");
        }
        glueLinkable =
            Optional.of(((NativeLinkableGroup) rule).getNativeLinkable(cxxPlatform, graphBuilder));
      }

      Set<MergedLibNativeLinkable> mergedLinkables =
          createLinkables(
              nativePlatforms.get(cpuType).getCxxPlatform(),
              cellPathResolver,
              cxxBuckConfig,
              downwardApiConfig,
              graphBuilder,
              buildTarget,
              projectFilesystem,
              glueLinkable,
              nativeLibraryMergeLocalizedSymbols.map(ImmutableSortedSet::copyOf),
              orderedConstituents);

      ImmutableMap.Builder<NativeLinkable, APKModule> linkableToModuleMapBuilder =
          ImmutableMap.builder();
      for (Map.Entry<APKModule, NativeLinkable> entry : linkables.entries()) {
        linkableToModuleMapBuilder.put(entry.getValue(), entry.getKey());
      }
      for (Map.Entry<APKModule, NativeLinkable> entry : assetLinkables.entries()) {
        linkableToModuleMapBuilder.put(entry.getValue(), entry.getKey());
      }
      ImmutableMap<NativeLinkable, APKModule> linkableToModuleMap =
          linkableToModuleMapBuilder.build();

      ImmutableMultimap.Builder<APKModule, NativeLinkable> moduleLinkablesBuilder =
          ImmutableListMultimap.builder();
      ImmutableMultimap.Builder<APKModule, NativeLinkable> moduleAssetLinkablesBuilder =
          ImmutableListMultimap.builder();

      for (MergedLibNativeLinkable linkable : mergedLinkables) {
        APKModule module = getModuleForLinkable(linkable, linkableToModuleMap);
        if (Collections.disjoint(linkable.constituents.getLinkables(), linkableAssetMap.keySet())) {
          moduleLinkablesBuilder.put(module, linkable);
        } else if (linkableAssetMap.keySet().containsAll(linkable.constituents.getLinkables())) {
          moduleAssetLinkablesBuilder.put(module, linkable);
        }

        for (NativeLinkable constituent : linkable.constituents.getLinkables()) {
          constituent
              .getSharedLibraries(graphBuilder)
              .forEach(
                  (soname, libraryPath) -> {
                    Optional<String> targetName =
                        libraryPath instanceof BuildTargetSourcePath
                            ? Optional.of(
                                ((BuildTargetSourcePath) libraryPath)
                                    .getTarget()
                                    .getUnflavoredBuildTarget()
                                    .toString())
                            : Optional.empty();

                    mapBuilder.accept(
                        linkable.constituents.isActuallyMerged(),
                        soname,
                        linkable.getSoname(),
                        targetName,
                        constituent.getIncludeInAndroidMergeMapOutput());
                  });
        }
      }

      mergedLinkablesBuilder.put(
          cpuType,
          NativeLinkableEnhancementResult.of(
              moduleLinkablesBuilder.build(), moduleAssetLinkablesBuilder.build()));
    }

    ImmutableSortedMap.Builder<String, ImmutableSortedSet<String>> finalSonameTargetsBuilder =
        ImmutableSortedMap.naturalOrder();
    sonameTargetsBuilder
        .build()
        .asMap()
        .forEach((k, v) -> finalSonameTargetsBuilder.put(k, ImmutableSortedSet.copyOf(v)));

    return ImmutableNativeLibraryMergeEnhancementResult.ofImpl(
        mergedLinkablesBuilder.build(),
        ImmutableSortedMap.copyOf(sonameMapBuilder),
        finalSonameTargetsBuilder.build());
  }

  private static APKModule getModuleForLinkable(
      MergedLibNativeLinkable linkable,
      ImmutableMap<NativeLinkable, APKModule> linkableToModuleMap) {
    APKModule module = null;
    for (NativeLinkable constituent : linkable.constituents.getLinkables()) {
      APKModule constituentModule = linkableToModuleMap.get(constituent);
      if (module == null) {
        module = constituentModule;
      }
      if (module != constituentModule) {
        StringBuilder sb = new StringBuilder();
        sb.append("Native library merge of ")
            .append(linkable)
            .append(" has inconsistent application module mappings: \n");
        HashMap<APKModule, List<NativeLinkable>> moduleToConstituent = new HashMap<>();
        for (NativeLinkable innerConstituent : linkable.constituents.getLinkables()) {
          APKModule innerConstituentModule = linkableToModuleMap.get(innerConstituent);
          moduleToConstituent.putIfAbsent(innerConstituentModule, new LinkedList<>());
          moduleToConstituent.get(innerConstituentModule).add(innerConstituent);
        }
        for (Map.Entry<APKModule, List<NativeLinkable>> apkModuleListEntry :
            moduleToConstituent.entrySet()) {
          sb.append("  Module ").append(apkModuleListEntry.getKey().getName()).append(": { \n");
          for (NativeLinkable nativeLinkable : apkModuleListEntry.getValue()) {
            sb.append("    ").append(nativeLinkable).append(",\n");
          }
          sb.append("  }\n");
        }
        throw new RuntimeException(
            "Native library merge of "
                + linkable
                + " has inconsistent application module mappings: "
                + sb);
      }
    }
    return Objects.requireNonNull(module);
  }

  private static Map<NativeLinkable, MergedNativeLibraryConstituents> makeConstituentMap(
      BuildTarget buildTarget,
      Optional<ImmutableMap<String, ImmutableList<Pattern>>> mergeMap,
      Optional<ImmutableList<Pair<String, ImmutableList<Pattern>>>> mergeSequence,
      Optional<ImmutableList<Pattern>> mergeSequenceBlocklist,
      ActionGraphBuilder graphBuilder,
      Iterable<NativeLinkable> allLinkables,
      ImmutableMap<NativeLinkable, APKModule> linkableAssetMap) {
    final List<MergedNativeLibraryConstituents> allConstituents;
    if (mergeMap.isPresent() && mergeSequence.isPresent()) {
      throw new HumanReadableException(
          String.format(
              "Error: %s specifies mutually exclusive native_library_merge_map "
                  + "and native_library_merge_sequence arguments",
              buildTarget));
    } else if (mergeMap.isPresent()) {
      allConstituents = makeConstituentListFromMergeMap(mergeMap.get(), allLinkables);
    } else {
      allConstituents =
          makeConstituentListFromMergeSequence(
              mergeSequence.get(),
              mergeSequenceBlocklist.orElse(ImmutableList.of()),
              graphBuilder,
              allLinkables,
              linkableAssetMap);
    }

    Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership = new HashMap<>();
    for (MergedNativeLibraryConstituents constituents : allConstituents) {
      boolean hasNonAssets = false;
      boolean hasAssets = false;

      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkableMembership.containsKey(linkable)) {
          throw new HumanReadableException(
              String.format(
                  "Error: When processing %s, attempted to merge %s into both %s and %s",
                  buildTarget, linkable, linkableMembership.get(linkable), constituents));
        }
        linkableMembership.put(linkable, constituents);

        if (linkableAssetMap.containsKey(linkable)) {
          hasAssets = true;
        } else {
          hasNonAssets = true;
        }
      }
      if (hasAssets && hasNonAssets) {
        StringBuilder sb = new StringBuilder();
        sb.append(
            String.format(
                "Error: When processing %s, merged lib '%s' contains both asset and non-asset libraries.\n",
                buildTarget, constituents));
        for (NativeLinkable linkable : constituents.getLinkables()) {
          sb.append(
              String.format(
                  "  %s -> %s\n",
                  linkable, linkableAssetMap.containsKey(linkable) ? "asset" : "not asset"));
        }
        throw new HumanReadableException(sb.toString());
      }
    }

    for (NativeLinkable linkable : allLinkables) {
      if (!linkableMembership.containsKey(linkable)) {
        linkableMembership.put(
            linkable,
            ImmutableMergedNativeLibraryConstituents.builder().addLinkables(linkable).build());
      }
    }
    return linkableMembership;
  }

  private static List<MergedNativeLibraryConstituents> makeConstituentListFromMergeMap(
      ImmutableMap<String, ImmutableList<Pattern>> mergeMap,
      Iterable<NativeLinkable> allLinkables) {
    List<MergedNativeLibraryConstituents> allConstituents = new ArrayList<>();
    for (ImmutableMap.Entry<String, ImmutableList<Pattern>> mergeConfigEntry :
        mergeMap.entrySet()) {
      String mergeSoname = mergeConfigEntry.getKey();
      List<Pattern> patterns = mergeConfigEntry.getValue();

      ImmutableMergedNativeLibraryConstituents.Builder constituentsBuilder =
          ImmutableMergedNativeLibraryConstituents.builder().setSoname(mergeSoname);

      for (Pattern pattern : patterns) {
        for (NativeLinkable linkable : allLinkables) {
          // TODO(dreiss): Might be a good idea to cache .getBuildTarget().toString().
          if (pattern
              .matcher(linkable.getBuildTarget().getUnflavoredBuildTarget().toString())
              .find()) {
            constituentsBuilder.addLinkables(linkable);
          }
        }
      }

      allConstituents.add(constituentsBuilder.build());
    }

    return allConstituents;
  }

  private static List<MergedNativeLibraryConstituents> makeConstituentListFromMergeSequence(
      final ImmutableList<Pair<String, ImmutableList<Pattern>>> mergeSequence,
      final ImmutableList<Pattern> mergeSequenceBlocklist,
      final ActionGraphBuilder graphBuilder,
      final Iterable<NativeLinkable> allLinkables,
      final ImmutableMap<NativeLinkable, APKModule> linkableAssetMap) {
    List<MergedNativeLibraryConstituents> allConstituents = new ArrayList<>();
    // Modules are loaded independently and cannot be assumed to be available at runtime. As a
    // result, it is safe to load an underlying library with module dependencies if and only if
    // that library or others with the same module dependencies have been *explicitly* requested,
    // indicating that the app layer believes its dependencies are available.
    //
    // Consequently, we must split up merged libraries by module dependencies so that we never
    // force a module to be loaded that would not be required by an unmerged library.
    Map<NativeLinkable, ImmutableSet<APKModule>> seenLinkablesToModuleDependencies =
        new HashMap<>();

    // Three types of linkables will not be merged at all. Their dependencies and dependents cannot
    // be merged with one another, as this would produce a dependency cycle.
    // - NativeLinkables without NativeLinkTargetInputs cannot be merged.
    // - Non-asset linkables are rare and often cannot be merged with other libraries. It's
    //   expeditious to simply not merge them.
    // - Linkables in native_library_merge_sequence_blocklist are explicitly excluded from merging.
    final ImmutableSet.Builder<NativeLinkable> excludedLinkablesBuilder = ImmutableSet.builder();
    for (NativeLinkable linkable : allLinkables) {
      if (!linkableAssetMap.containsKey(linkable)
          || !doesLinkableHaveLinkTargetInput(linkable, graphBuilder)) {
        excludedLinkablesBuilder.add(linkable);
        continue;
      }

      for (Pattern blockedPattern : mergeSequenceBlocklist) {
        if (blockedPattern
            .matcher(linkable.getBuildTarget().getUnflavoredBuildTarget().toString())
            .find()) {
          excludedLinkablesBuilder.add(linkable);
          break;
        }
      }
    }

    final ImmutableSet<NativeLinkable> excludedLinkables = excludedLinkablesBuilder.build();

    // Merge sequence entries are prioritized by order; later entries will not merge any targets
    // already visited by earlier entries.
    for (Pair<String, ImmutableList<Pattern>> mergedNativeLibraryDefinition : mergeSequence) {
      String mergedSoName = mergedNativeLibraryDefinition.getFirst();
      List<Pattern> rootTargetPatterns = mergedNativeLibraryDefinition.getSecond();

      final ImmutableSet.Builder<NativeLinkable> newMergedLinkablesBuilder = ImmutableSet.builder();
      final ImmutableSet.Builder<NativeLinkable> newUnmergedLinkablesBuilder =
          ImmutableSet.builder();

      // Visit each previously-unvisited linkable in the dependency graphs of root targets,
      // recording their module dependencies.
      for (Pattern rootTargetPattern : rootTargetPatterns) {
        for (NativeLinkable linkable : allLinkables) {
          if (rootTargetPattern
              .matcher(linkable.getBuildTarget().getUnflavoredBuildTarget().toString())
              .find()) {
            traverseDependencyGraphAndRecordModuleDependencies(
                linkable,
                seenLinkablesToModuleDependencies,
                graphBuilder,
                linkableAssetMap,
                excludedLinkables,
                newMergedLinkablesBuilder,
                newUnmergedLinkablesBuilder);
          }
        }
      }

      final ImmutableSet<NativeLinkable> newMergedLinkables = newMergedLinkablesBuilder.build();
      final ImmutableSet<NativeLinkable> newUnmergedLinkables = newUnmergedLinkablesBuilder.build();

      // Construct the *dependent* graph - the reverse of the dependency graph - among new merged
      // linkables dependent on new unmerged linkables.
      final Set<NativeLinkable> newUnmergedLinkableDependencies = new HashSet<>();
      final ImmutableMultimap.Builder<NativeLinkable, NativeLinkable>
          newUnmergedLinkableDependencyDependentGraphBuilder = ImmutableListMultimap.builder();
      for (NativeLinkable newUnmergedLinkable : newUnmergedLinkables) {
        traverseNewDependencyGraphAndConstructDependentGraph(
            newUnmergedLinkable,
            graphBuilder,
            newMergedLinkables,
            newUnmergedLinkables,
            newUnmergedLinkableDependencies,
            newUnmergedLinkableDependencyDependentGraphBuilder);
      }
      final ImmutableMultimap<NativeLinkable, NativeLinkable>
          newUnmergedLinkableDependencyDependentGraph =
              newUnmergedLinkableDependencyDependentGraphBuilder.build();

      // Visit each node in this dependent graph and record their new unmerged linkable dependents.
      final Map<NativeLinkable, ImmutableSet<NativeLinkable>>
          newLinkablesToNewUnmergedLinkableDependents = new HashMap<>();
      for (NativeLinkable newUnmergedLinkableDependency : newUnmergedLinkableDependencies) {
        traverseNewDependentGraphAndRecordNewUnmergedLinkableDependents(
            newUnmergedLinkableDependency,
            newUnmergedLinkables,
            newUnmergedLinkableDependencyDependentGraph,
            newLinkablesToNewUnmergedLinkableDependents);
      }

      final ImmutableMultimap.Builder<MergeSequenceLinkableGrouping, NativeLinkable>
          linkableGroupingsBuilder = ImmutableListMultimap.builder();

      for (NativeLinkable newMergedLinkable : newMergedLinkables) {
        linkableGroupingsBuilder.put(
            new MergeSequenceLinkableGrouping(
                seenLinkablesToModuleDependencies.get(newMergedLinkable),
                linkableAssetMap.get(newMergedLinkable).isRootModule(),
                newLinkablesToNewUnmergedLinkableDependents.getOrDefault(
                    newMergedLinkable, ImmutableSet.of())),
            newMergedLinkable);
      }

      // To reduce churn, the linkable sets are sorted - see MergedSequenceLinkableGrouping for
      // details. The first set is the only one we expect for most merge sequence steps, so its SO
      // name is left unsuffixed. Each other linkable set's SO name is suffixed with its list index.
      Iterable<Collection<NativeLinkable>> sortedLinkableSets =
          linkableGroupingsBuilder.build().asMap().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(Map.Entry::getValue)
              .collect(ImmutableList.toImmutableList());
      int mergedLibraryCounter = 0;
      for (Collection<NativeLinkable> seenLinkables : sortedLinkableSets) {
        String finalMergedSoName = mergedSoName;
        if (mergedLibraryCounter > 0) {
          finalMergedSoName = finalMergedSoName.replace(".", "_" + mergedLibraryCounter + ".");
        }

        allConstituents.add(
            ImmutableMergedNativeLibraryConstituents.builder()
                .setSoname(finalMergedSoName)
                .addAllLinkables(seenLinkables)
                .build());

        mergedLibraryCounter++;
      }
    }

    return allConstituents;
  }

  private static ImmutableSet<APKModule> traverseDependencyGraphAndRecordModuleDependencies(
      final NativeLinkable linkable,
      final Map<NativeLinkable, ImmutableSet<APKModule>> seenLinkablesToModuleDependencies,
      final ActionGraphBuilder graphBuilder,
      final ImmutableMap<NativeLinkable, APKModule> linkableAssetMap,
      final ImmutableSet<NativeLinkable> excludedLinkables,
      final ImmutableSet.Builder<NativeLinkable> newMergedLinkablesBuilder,
      final ImmutableSet.Builder<NativeLinkable> newUnmergedLinkablesBuilder) {
    // Each seen linkable has been or is about to be assigned to a constituent builder.
    if (seenLinkablesToModuleDependencies.containsKey(linkable)) {
      return seenLinkablesToModuleDependencies.get(linkable);
    }

    final ImmutableSet.Builder<APKModule> moduleDependenciesBuilder = ImmutableSet.builder();
    for (NativeLinkable dep :
        Iterables.concat(
            linkable.getNativeLinkableDeps(graphBuilder),
            linkable.getNativeLinkableExportedDeps(graphBuilder))) {
      moduleDependenciesBuilder.addAll(
          traverseDependencyGraphAndRecordModuleDependencies(
              dep,
              seenLinkablesToModuleDependencies,
              graphBuilder,
              linkableAssetMap,
              excludedLinkables,
              newMergedLinkablesBuilder,
              newUnmergedLinkablesBuilder));
    }

    final APKModule containingModule = linkableAssetMap.get(linkable);
    if (containingModule != null) {
      moduleDependenciesBuilder.add(containingModule);
    }
    final ImmutableSet<APKModule> moduleDependencies = moduleDependenciesBuilder.build();

    if (excludedLinkables.contains(linkable)) {
      newUnmergedLinkablesBuilder.add(linkable);
    } else {
      newMergedLinkablesBuilder.add(linkable);
    }

    seenLinkablesToModuleDependencies.put(linkable, moduleDependencies);

    return moduleDependencies;
  }

  private static void traverseNewDependencyGraphAndConstructDependentGraph(
      final NativeLinkable linkable,
      final ActionGraphBuilder graphBuilder,
      final ImmutableSet<NativeLinkable> newMergedLinkables,
      final ImmutableSet<NativeLinkable> newUnmergedLinkables,
      final Set<NativeLinkable> newUnmergedLinkableDependencies,
      final ImmutableMultimap.Builder<NativeLinkable, NativeLinkable>
          newUnmergedLinkableDependencyDependentGraphBuilder) {
    // We only need to visit each node once, as we check each edge on the first visit.
    if (newUnmergedLinkableDependencies.contains(linkable)) {
      return;
    }
    newUnmergedLinkableDependencies.add(linkable);

    for (NativeLinkable dep :
        Iterables.concat(
            linkable.getNativeLinkableDeps(graphBuilder),
            linkable.getNativeLinkableExportedDeps(graphBuilder))) {
      // Only new nodes need to be split up, so we exclude others from the dependent graph.
      if (!newMergedLinkables.contains(linkable) && !newUnmergedLinkables.contains(linkable)) {
        continue;
      }

      newUnmergedLinkableDependencyDependentGraphBuilder.put(dep, linkable);

      traverseNewDependencyGraphAndConstructDependentGraph(
          dep,
          graphBuilder,
          newMergedLinkables,
          newUnmergedLinkables,
          newUnmergedLinkableDependencies,
          newUnmergedLinkableDependencyDependentGraphBuilder);
    }
  }

  private static ImmutableSet<NativeLinkable>
      traverseNewDependentGraphAndRecordNewUnmergedLinkableDependents(
          final NativeLinkable linkable,
          final ImmutableSet<NativeLinkable> newUnmergedLinkables,
          final ImmutableMultimap<NativeLinkable, NativeLinkable>
              newUnmergedLinkableDependencyDependentGraph,
          final Map<NativeLinkable, ImmutableSet<NativeLinkable>>
              newLinkablesToNewUnmergedLinkableDependents) {
    // We only need to visit each node once, as we record all dependents on the first visit.
    if (newLinkablesToNewUnmergedLinkableDependents.containsKey(linkable)) {
      return newLinkablesToNewUnmergedLinkableDependents.get(linkable);
    }

    final ImmutableSet.Builder<NativeLinkable> newUnmergedLinkableDependentsBuilder =
        ImmutableSet.builder();
    for (NativeLinkable dep : newUnmergedLinkableDependencyDependentGraph.get(linkable)) {
      newUnmergedLinkableDependentsBuilder.addAll(
          traverseNewDependentGraphAndRecordNewUnmergedLinkableDependents(
              dep,
              newUnmergedLinkables,
              newUnmergedLinkableDependencyDependentGraph,
              newLinkablesToNewUnmergedLinkableDependents));
    }

    if (newUnmergedLinkables.contains(linkable)) {
      newUnmergedLinkableDependentsBuilder.add(linkable);
    }

    final ImmutableSet<NativeLinkable> newUnmergedLinkableDependents =
        newUnmergedLinkableDependentsBuilder.build();

    newLinkablesToNewUnmergedLinkableDependents.put(linkable, newUnmergedLinkableDependents);

    return newUnmergedLinkableDependents;
  }

  private static boolean doesLinkableHaveLinkTargetInput(
      final NativeLinkable linkable, final ActionGraphBuilder graphBuilder) {
    final Optional<NativeLinkTarget> nativeLinkTarget =
        linkable.getNativeLinkTarget(graphBuilder, true, false);
    if (!nativeLinkTarget.isPresent()) {
      return false;
    }

    // NativeLinkTargetInput lookup can throw.
    try {
      return nativeLinkTarget
              .get()
              .getNativeLinkTargetInput(graphBuilder, graphBuilder.getSourcePathResolver())
          != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** A simple helper interface for building the soname map. */
  interface SonameMapBuilder {
    void accept(
        boolean isActuallyMerged,
        String originalName,
        String mergedName,
        Optional<String> targetName,
        boolean includeInAndroidMergeMapOutput);
  }

  /** Topo-sort the constituents objects so we can process deps first. */
  private static Iterable<MergedNativeLibraryConstituents> getOrderedMergedConstituents(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership) {
    MutableDirectedGraph<MergedNativeLibraryConstituents> graph = new MutableDirectedGraph<>();
    for (MergedNativeLibraryConstituents constituents : linkableMembership.values()) {
      graph.addNode(constituents);
      for (NativeLinkable constituentLinkable : constituents.getLinkables()) {
        // For each dep of each constituent of each merged lib...
        for (NativeLinkable dep :
            Iterables.concat(
                constituentLinkable.getNativeLinkableDeps(graphBuilder),
                constituentLinkable.getNativeLinkableExportedDeps(graphBuilder))) {
          // If that dep is in a different merged lib, add a dependency.
          MergedNativeLibraryConstituents mergedDep =
              Objects.requireNonNull(linkableMembership.get(dep));
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
      while (!partialCycle.contains(item)) {
        partialCycle.add(item);
        item =
            Sets.intersection(ImmutableSet.copyOf(graph.getOutgoingNodesFor(item)), fullCycle)
                .iterator()
                .next();
      }

      StringBuilder cycleString = new StringBuilder().append("[ ");
      StringBuilder depString = new StringBuilder();
      boolean foundStart = false;
      MergedNativeLibraryConstituents prevMember = null;
      for (MergedNativeLibraryConstituents member : partialCycle) {
        if (member == item) {
          foundStart = true;
        }
        if (foundStart) {
          cycleString.append(member);
          cycleString.append(" -> ");
        }
        if (prevMember != null) {
          Set<Pair<String, String>> depEdges =
              getRuleDependencies(graphBuilder, linkableMembership, prevMember, member);
          depString.append(formatRuleDependencies(depEdges, prevMember, member));
        }
        prevMember = member;
      }
      cycleString.append(item);
      cycleString.append(" ]");

      Set<Pair<String, String>> depEdges =
          getRuleDependencies(
              graphBuilder, linkableMembership, Objects.requireNonNull(prevMember), item);
      depString.append(formatRuleDependencies(depEdges, Objects.requireNonNull(prevMember), item));

      throw new HumanReadableException(
          "Error: Dependency cycle detected when merging native libs for "
              + buildTarget
              + ": "
              + cycleString
              + "\n"
              + depString);
    }

    return TopologicalSort.sort(graph);
  }

  /**
   * Calculates the actual target dependency edges between two merged libraries. Returns them as
   * strings for printing.
   */
  private static Set<Pair<String, String>> getRuleDependencies(
      ActionGraphBuilder graphBuilder,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership,
      MergedNativeLibraryConstituents from,
      MergedNativeLibraryConstituents to) {

    // We do this work again because we want to avoid storing extraneous information on the
    // normal path. We know we're iterating over a cycle, so we can afford to do some work to
    // figure out the actual targets causing it.
    Set<Pair<String, String>> buildTargets = new LinkedHashSet<>();
    for (NativeLinkable sourceLinkable : from.getLinkables()) {
      for (NativeLinkable targetLinkable :
          Iterables.concat(
              sourceLinkable.getNativeLinkableDeps(graphBuilder),
              sourceLinkable.getNativeLinkableExportedDeps(graphBuilder))) {
        if (linkableMembership.get(targetLinkable) == to) {
          // Normalize to string names for printing.
          buildTargets.add(
              new Pair<>(
                  sourceLinkable.getBuildTarget().toString(),
                  targetLinkable.getBuildTarget().toString()));
        }
      }
    }
    return buildTargets;
  }

  private static String formatRuleDependencies(
      Set<Pair<String, String>> edges,
      MergedNativeLibraryConstituents from,
      MergedNativeLibraryConstituents to) {
    StringBuilder depString = new StringBuilder();
    depString.append("Dependencies between ").append(from).append(" and ").append(to).append(":\n");
    for (Pair<String, String> ruleEdge : edges) {
      depString
          .append("  ")
          .append(ruleEdge.getFirst())
          .append(" -> ")
          .append(ruleEdge.getSecond())
          .append("\n");
    }
    return depString.toString();
  }

  /** Create the final Linkables that will be passed to the later stages of graph enhancement. */
  private static Set<MergedLibNativeLinkable> createLinkables(
      CxxPlatform cxxPlatform,
      CellPathResolver cellPathResolver,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      ActionGraphBuilder graphBuilder,
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      Optional<NativeLinkable> glueLinkable,
      Optional<ImmutableSortedSet<String>> symbolsToLocalize,
      Iterable<MergedNativeLibraryConstituents> orderedConstituents) {
    // Map from original linkables to the Linkables they have been merged into.
    Map<NativeLinkable, MergedLibNativeLinkable> mergeResults = new HashMap<>();

    for (MergedNativeLibraryConstituents constituents : orderedConstituents) {
      ImmutableCollection<NativeLinkable> preMergeLibs = constituents.getLinkables();

      List<MergedLibNativeLinkable> orderedDeps =
          getStructuralDeps(constituents, x -> x.getNativeLinkableDeps(graphBuilder), mergeResults);
      List<MergedLibNativeLinkable> orderedExportedDeps =
          getStructuralDeps(
              constituents, x -> x.getNativeLinkableExportedDeps(graphBuilder), mergeResults);

      ProjectFilesystem targetProjectFilesystem = projectFilesystem;
      if (!constituents.isActuallyMerged()) {
        // There is only one target
        BuildTarget target = preMergeLibs.iterator().next().getBuildTarget();
        // Switch the target project filesystem
        targetProjectFilesystem = graphBuilder.getRule(target).getProjectFilesystem();
      }

      MergedLibNativeLinkable mergedLinkable =
          new MergedLibNativeLinkable(
              cxxPlatform,
              cellPathResolver,
              cxxBuckConfig,
              downwardApiConfig,
              graphBuilder,
              baseBuildTarget,
              targetProjectFilesystem,
              constituents,
              orderedDeps,
              orderedExportedDeps,
              glueLinkable,
              symbolsToLocalize);

      for (NativeLinkable lib : preMergeLibs) {
        // Track what was merged into this so later linkables can find us as a dependency.
        mergeResults.put(lib, mergedLinkable);
      }
    }

    return ImmutableSortedSet.copyOf(
        Comparator.comparing(NativeLinkable::getBuildTarget), mergeResults.values());
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
    Map<MergedLibNativeLinkable, Unit> structuralDeps = new HashMap<>();
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
        structuralDeps.put(mappedDep, Unit.UNIT);
      }
    }
    // Sort here to ensure consistent ordering, because the build target depends on the order.
    return structuralDeps.keySet().stream()
        .sorted(Comparator.comparing(MergedLibNativeLinkable::getBuildTarget))
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Data object for internal use, representing the source libraries getting merged together into
   * one DSO. Libraries not being merged will have one linkable and no soname.
   */
  @BuckStyleValueWithBuilder
  abstract static class MergedNativeLibraryConstituents
      implements Comparable<MergedNativeLibraryConstituents> {
    public abstract Optional<String> getSoname();

    public abstract ImmutableSet<NativeLinkable> getLinkables();

    /** @return true if this is a library defined in the merge config. */
    public boolean isActuallyMerged() {
      return getSoname().isPresent();
    }

    @Value.Check
    protected void check() {
      if (!isActuallyMerged()) {
        Preconditions.checkArgument(
            getLinkables().size() == 1,
            "BUG: %s is not 'actually merged', but does not consist of a single linkable");
      }
    }

    @Override
    public String toString() {
      if (isActuallyMerged()) {
        return "merge:" + getSoname().get();
      }
      return "no-merge:" + getLinkables().iterator().next().getBuildTarget();
    }

    @Override
    public int compareTo(MergedNativeLibraryConstituents other) {
      return toString().compareTo(other.toString());
    }
  }

  /** A data object to hold the result of native library merge enhancement. */
  @BuckStyleValue
  abstract static class NativeLibraryMergeEnhancementResult {
    /** A {@link NativeLinkableEnhancementResult} for each cpu type. */
    public abstract ImmutableMap<TargetCpuType, NativeLinkableEnhancementResult>
        getMergedLinkables();

    /**
     * Contains a map of original soname to merged soname and other data like whether library should
     * be included in merge map output.
     */
    public abstract ImmutableSortedMap<String, SonameMergeData> getSonameMapping();

    /**
     * This is for human consumption only. It records all the build targets merged into each lib.
     */
    public abstract ImmutableSortedMap<String, ImmutableSortedSet<String>> getSharedObjectTargets();
  }

  /** A data object to hold information about merged so library. */
  @BuckStyleValue
  abstract static class SonameMergeData {
    /** Name of the merged library that this is part of. */
    public abstract String getSoname();

    /** Whether this library should be included in so merge map output code. */
    public abstract boolean getIncludeInAndroidMergeMapOutput();
  }

  /**
   * A data object that identifies the groups into which the mergeable linkables in a merge sequence
   * step are split.
   *
   * <p>Linkables must be split by module dependencies so that loading an individual target does not
   * require any modules to be loaded that it does not actually depend on.
   *
   * <p>Dependencies of non-root modules with static linkage are added to the root module rather
   * than a shared module, so grouping linkables purely by module dependencies would be ambiguous
   * between non-root-module linkables <i>depending</i> on the root module and their root-module
   * <i>dependents</i>. We distinguish these groups by tracking linkables' root-module membership.
   *
   * <p>Finally, we also split linkables by their sets of <i>dependent</i> linkables that are new to
   * this step and cannot be merged; merging dependents and dependencies of these linkables into the
   * same library results in a circular dependency.
   */
  private static class MergeSequenceLinkableGrouping
      implements Comparable<MergeSequenceLinkableGrouping> {
    private final ImmutableCollection<APKModule> moduleDependencies;
    private final boolean isInRootModule;
    private final ImmutableCollection<NativeLinkable> newUnmergedLinkableDependents;

    private MergeSequenceLinkableGrouping(
        ImmutableCollection<APKModule> moduleDependencies,
        boolean isInRootModule,
        ImmutableCollection<NativeLinkable> newUnmergedLinkableDependents) {
      this.moduleDependencies = moduleDependencies;
      this.isInRootModule = isInRootModule;
      this.newUnmergedLinkableDependents = newUnmergedLinkableDependents;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MergeSequenceLinkableGrouping other = (MergeSequenceLinkableGrouping) o;
      return moduleDependencies.equals(other.moduleDependencies)
          && isInRootModule == other.isInRootModule
          && newUnmergedLinkableDependents.equals(other.newUnmergedLinkableDependents);
    }

    @Override
    public int hashCode() {
      return Objects.hash(moduleDependencies, isInRootModule, newUnmergedLinkableDependents);
    }

    /**
     * Groupings are sorted first by descending module dependency count, then by reverse module
     * dependency set lexicographical order, breaking ties by putting root module linkables first,
     * then finally by ascending unmerged linkable dependent count and unmerged linkable dependent
     * set lexicographical order. This ordering generally places targets closer to roots first, and
     * the very first set always contains at least one root target.
     */
    @Override
    public int compareTo(MergeSequenceLinkableGrouping other) {
      if (this == other) {
        return 0;
      }

      return ComparisonChain.start()
          .compare(other.moduleDependencies.size(), this.moduleDependencies.size())
          .compare(
              other.moduleDependencies,
              this.moduleDependencies,
              Comparators.lexicographical(Comparator.<APKModule>naturalOrder()))
          .compareTrueFirst(this.isInRootModule, other.isInRootModule)
          .compare(
              this.newUnmergedLinkableDependents.size(), other.newUnmergedLinkableDependents.size())
          .compare(
              this.newUnmergedLinkableDependents,
              other.newUnmergedLinkableDependents,
              Comparators.lexicographical(Comparator.comparing(NativeLinkable::getBuildTarget)))
          .result();
    }
  }

  /**
   * Our own implementation of NativeLinkable, which is consumed by later phases of graph
   * enhancement. It represents a single merged library.
   */
  private static class MergedLibNativeLinkable implements NativeLinkable {
    private final CxxPlatform cxxPlatform;
    private final CxxBuckConfig cxxBuckConfig;
    private final DownwardApiConfig downwardApiConfig;
    private final ActionGraphBuilder graphBuilder;
    private final ProjectFilesystem projectFilesystem;
    private final MergedNativeLibraryConstituents constituents;
    private final Optional<NativeLinkable> glueLinkable;
    private final Optional<ImmutableSortedSet<String>> symbolsToLocalize;
    private final Map<NativeLinkable, MergedLibNativeLinkable> mergedDepMap;
    private final BuildTarget buildTarget;
    private final boolean canUseOriginal;
    private final CellPathResolver cellPathResolver;
    // Note: update constructBuildTarget whenever updating new fields.

    MergedLibNativeLinkable(
        CxxPlatform cxxPlatform,
        CellPathResolver cellPathResolver,
        CxxBuckConfig cxxBuckConfig,
        DownwardApiConfig downwardApiConfig,
        ActionGraphBuilder graphBuilder,
        BuildTarget baseBuildTarget,
        ProjectFilesystem projectFilesystem,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable,
        Optional<ImmutableSortedSet<String>> symbolsToLocalize) {
      this.cxxPlatform = cxxPlatform;
      this.cellPathResolver = cellPathResolver;
      this.cxxBuckConfig = cxxBuckConfig;
      this.downwardApiConfig = downwardApiConfig;
      this.graphBuilder = graphBuilder;
      this.projectFilesystem = projectFilesystem;
      this.constituents = constituents;
      this.glueLinkable = glueLinkable;
      this.symbolsToLocalize = symbolsToLocalize;

      Iterable<MergedLibNativeLinkable> allDeps =
          Iterables.concat(orderedDeps, orderedExportedDeps);
      Map<NativeLinkable, MergedLibNativeLinkable> mergedDeps = new HashMap<>();
      for (MergedLibNativeLinkable dep : allDeps) {
        for (NativeLinkable linkable : dep.constituents.getLinkables()) {
          MergedLibNativeLinkable old = mergedDeps.put(linkable, dep);
          if (old != null && old != dep) {
            throw new RuntimeException(
                String.format(
                    "BUG: When processing %s, dep %s mapped to both %s and %s",
                    constituents, linkable, dep, old));
          }
        }
      }
      mergedDepMap = Collections.unmodifiableMap(mergedDeps);

      canUseOriginal = computeCanUseOriginal(constituents, allDeps);

      buildTarget =
          constructBuildTarget(
              baseBuildTarget,
              constituents,
              orderedDeps,
              orderedExportedDeps,
              glueLinkable,
              symbolsToLocalize);
    }

    /**
     * If a library is not involved in merging, and neither are any of its transitive deps, we can
     * use just the original shared object, which lets us share cache with apps that don't use
     * merged libraries at all.
     */
    private static boolean computeCanUseOriginal(
        MergedNativeLibraryConstituents constituents, Iterable<MergedLibNativeLinkable> allDeps) {
      if (constituents.isActuallyMerged()) {
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
    String getSoname() {
      if (constituents.isActuallyMerged()) {
        return constituents.getSoname().get();
      }
      ImmutableMap<String, SourcePath> shared =
          constituents.getLinkables().iterator().next().getSharedLibraries(graphBuilder);
      Preconditions.checkState(shared.size() == 1);
      return shared.keySet().iterator().next();
    }

    @Override
    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    private static BuildTarget constructBuildTarget(
        BuildTarget baseBuildTarget,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable,
        Optional<ImmutableSortedSet<String>> symbolsToLocalize) {
      BuildTarget initialTarget;
      if (!constituents.isActuallyMerged()) {
        // This library isn't really merged.
        // We use its constituent as the base target to ensure that
        // it is shared between all apps with the same merge structure.
        initialTarget = constituents.getLinkables().iterator().next().getBuildTarget();
      } else {
        // If we're merging, construct a base target in the app's directory.
        // This ensure that all apps in this directory will
        // have a chance to share the target.
        initialTarget =
            baseBuildTarget
                .withoutFlavors()
                .withShortName(
                    "merged_lib_"
                        + Flavor.replaceInvalidCharacters(constituents.getSoname().get()));
      }

      // Two merged libs (for different apps) can have the same constituents,
      // but they still need to be separate rules if their dependencies differ.
      // However, we want to share if possible to share cache artifacts.
      // Therefore, transitively hash the dependencies' targets
      // to create a unique string to add to our target.
      Hasher hasher = Hashing.murmur3_32().newHasher();
      for (NativeLinkable nativeLinkable : constituents.getLinkables()) {
        hasher.putString(nativeLinkable.getBuildTarget().toString(), StandardCharsets.UTF_8);
        hasher.putChar('^');
      }
      // Hash all the merged deps, in order.
      hasher.putString("__DEPS__^", StandardCharsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), StandardCharsets.UTF_8);
        hasher.putChar('^');
      }
      // Separate exported deps.  This doesn't affect linking, but it can affect our dependents
      // if we're building two apps at once.
      hasher.putString("__EXPORT__^", StandardCharsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedExportedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), StandardCharsets.UTF_8);
        hasher.putChar('^');
      }

      // Glue can vary per-app, so include that in the hash as well.
      if (glueLinkable.isPresent()) {
        hasher.putString("__GLUE__^", StandardCharsets.UTF_8);
        hasher.putString(glueLinkable.get().getBuildTarget().toString(), StandardCharsets.UTF_8);
        hasher.putChar('^');
      }

      // Symbols to localize can vary per-app, so include that in the hash as well.
      if (symbolsToLocalize.isPresent()) {
        hasher.putString("__LOCALIZE__^", StandardCharsets.UTF_8);
        hasher.putString(Joiner.on(',').join(symbolsToLocalize.get()), StandardCharsets.UTF_8);
        hasher.putChar('^');
      }

      String mergeFlavor = "merge_structure_" + hasher.hash();

      return initialTarget.withAppendedFlavors(InternalFlavor.of(mergeFlavor));
    }

    private BuildTarget getBuildTargetForPlatform(CxxPlatform cxxPlatform) {
      return getBuildTarget().withAppendedFlavors(cxxPlatform.getFlavor());
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDeps(
        ActionGraphBuilder graphBuilder) {
      return getMappedDeps(x -> x.getNativeLinkableDeps(graphBuilder));
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
        ActionGraphBuilder graphBuilder) {
      return getMappedDeps(x -> x.getNativeLinkableExportedDeps(graphBuilder));
    }

    private Iterable<? extends NativeLinkable> getMappedDeps(
        Function<NativeLinkable, Iterable<? extends NativeLinkable>> depType) {
      ImmutableList.Builder<NativeLinkable> builder = ImmutableList.builder();

      for (NativeLinkable linkable : constituents.getLinkables()) {
        for (NativeLinkable dep : depType.apply(linkable)) {
          // Don't try to depend on ourselves.
          if (!constituents.getLinkables().contains(dep)) {
            builder.add(Objects.requireNonNull(mergedDepMap.get(dep)));
          }
        }
      }

      return builder.build();
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration,
        boolean preferStripped) {

      // This path gets taken for a force-static library.
      if (type == Linker.LinkableDepType.STATIC_PIC) {
        ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
        for (NativeLinkable linkable : constituents.getLinkables()) {
          builder.add(
              linkable.getNativeLinkableInput(
                  Linker.LinkableDepType.STATIC_PIC, graphBuilder, targetConfiguration));
        }
        return NativeLinkableInput.concat(builder.build());
      }

      // STATIC isn't valid because we always need PIC on Android.
      Preconditions.checkArgument(type == Linker.LinkableDepType.SHARED);

      ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
      ImmutableMap<String, SourcePath> sharedLibraries = getSharedLibraries(graphBuilder);
      for (SourcePath sharedLib : sharedLibraries.values()) {
        // If we have a shared library, our dependents should link against it.
        // Might be multiple shared libraries if prebuilts are included.
        argsBuilder.add(SourcePathArg.of(sharedLib));
      }

      // If our constituents have exported linker flags, our dependents should use them.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        argsBuilder.addAll(linkable.getExportedLinkerFlags(graphBuilder));
      }

      // If our constituents have post exported linker flags, our dependents should use them.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        argsBuilder.addAll(linkable.getExportedPostLinkerFlags(graphBuilder));
      }

      return NativeLinkableInput.of(argsBuilder.build(), ImmutableSet.of(), ImmutableSet.of());
    }

    @Override
    public Optional<NativeLinkTarget> getNativeLinkTarget(
        ActionGraphBuilder graphBuilder,
        boolean includePrivateLinkerFlags,
        boolean preferStripped) {
      return Optional.empty();
    }

    private NativeLinkableInput getImmediateNativeLinkableInput(
        CxxPlatform cxxPlatform,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {
      Linker linker = cxxPlatform.getLd().resolve(graphBuilder, targetConfiguration);
      ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
      ImmutableList<NativeLinkable> usingGlue = ImmutableList.of();
      if (glueLinkable.isPresent() && constituents.isActuallyMerged()) {
        usingGlue = ImmutableList.of(glueLinkable.get());
      }

      for (NativeLinkable linkable : Iterables.concat(usingGlue, constituents.getLinkables())) {
        Optional<NativeLinkTarget> nativeLinkTarget =
            linkable.getNativeLinkTarget(graphBuilder, true, false);
        if (nativeLinkTarget.isPresent()) {
          // If this constituent is a NativeLinkTarget, use its input to get raw objects and
          // linker flags.
          builder.add(
              nativeLinkTarget
                  .get()
                  .getNativeLinkTargetInput(graphBuilder, graphBuilder.getSourcePathResolver()));
        } else {
          // Otherwise, just get the static pic output.
          NativeLinkableInput staticPic =
              linkable.getNativeLinkableInput(
                  Linker.LinkableDepType.STATIC_PIC, graphBuilder, targetConfiguration);
          builder.add(
              staticPic.withArgs(
                  ImmutableList.copyOf(
                      FluentIterable.from(staticPic.getArgs())
                          .transformAndConcat(
                              arg ->
                                  linker.linkWhole(arg, graphBuilder.getSourcePathResolver())))));
        }
      }
      return NativeLinkableInput.concat(builder.build());
    }

    @Override
    public NativeLinkableGroup.Linkage getPreferredLinkage() {
      // If we have any non-static constituents, our preferred linkage is shared
      // (because stuff in Android is shared by default).  That's the common case.
      // If *all* of our constituents are force_static=True, we will also be preferred static.
      // Most commonly, that will happen when we're just wrapping a single force_static constituent.
      // It's also possible that multiple force_static libs could be merged,
      // but that has no effect.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC) {
          return NativeLinkableGroup.Linkage.SHARED;
        }
      }

      return NativeLinkableGroup.Linkage.STATIC;
    }

    @Override
    public boolean getIncludeInAndroidMergeMapOutput() {
      return true;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder) {
      if (getPreferredLinkage() == NativeLinkableGroup.Linkage.STATIC) {
        return ImmutableMap.of();
      }

      ImmutableMap<String, SourcePath> originalSharedLibraries =
          constituents.getLinkables().iterator().next().getSharedLibraries(graphBuilder);
      if (canUseOriginal
          || (!constituents.isActuallyMerged() && originalSharedLibraries.isEmpty())) {
        return originalSharedLibraries;
      }

      String soname = getSoname();
      BuildRule rule =
          graphBuilder.computeIfAbsent(
              getBuildTargetForPlatform(cxxPlatform),
              target ->
                  CxxLinkableEnhancer.createCxxLinkableBuildRule(
                      cxxBuckConfig,
                      downwardApiConfig,
                      cxxPlatform,
                      projectFilesystem,
                      graphBuilder,
                      target,
                      Linker.LinkType.SHARED,
                      Optional.of(soname),
                      BuildTargetPaths.getGenPath(
                              projectFilesystem.getBuckPaths(), target, "%s/" + getSoname())
                          .getPath(),
                      ImmutableList.of(),
                      // Android Binaries will use share deps by default.
                      Linker.LinkableDepType.SHARED,
                      Optional.empty(),
                      CxxLinkOptions.of(),
                      Iterables.concat(
                          getNativeLinkableDeps(graphBuilder),
                          getNativeLinkableExportedDeps(graphBuilder)),
                      Optional.empty(),
                      Optional.empty(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      getImmediateNativeLinkableInput(
                          cxxPlatform, graphBuilder, target.getTargetConfiguration()),
                      constituents.isActuallyMerged()
                          ? symbolsToLocalize.map(SymbolLocalizingPostprocessor::new)
                          : Optional.empty(),
                      cellPathResolver));
      return ImmutableMap.of(soname, Objects.requireNonNull(rule.getSourcePathToOutput()));
    }

    @Override
    public boolean shouldBeLinkedInAppleTestAndHost() {
      return false;
    }
  }

  private static class SymbolLocalizingPostprocessor implements LinkOutputPostprocessor {
    @AddToRuleKey private final ImmutableSortedSet<String> symbolsToLocalize;

    @AddToRuleKey private final String postprocessorType = "localize-dynamic-symbols";

    SymbolLocalizingPostprocessor(ImmutableSortedSet<String> symbolsToLocalize) {
      this.symbolsToLocalize = symbolsToLocalize;
    }

    @Override
    public ImmutableList<Step> getSteps(BuildContext context, Path linkOutput, Path finalOutput) {
      return ImmutableList.of(
          new Step() {
            @Override
            public StepExecutionResult execute(StepExecutionContext context) throws IOException {
              // Copy the output into place, then fix it in-place with mmap.
              Files.copy(linkOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING);

              try (FileChannel channel =
                  FileChannel.open(
                      finalOutput, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                try (ByteBufferUnmapper unmapper =
                    ByteBufferUnmapper.createUnsafe(
                        channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size()))) {
                  Elf elf = new Elf(unmapper.getByteBuffer());
                  fixSection(elf, ".dynsym", ".dynstr");
                  fixSection(elf, ".symtab", ".strtab");
                }
              }
              return StepExecutionResults.SUCCESS;
            }

            void fixSection(Elf elf, String sectionName, String stringSectionName)
                throws IOException {
              ElfSection section =
                  elf.getMandatorySectionByName(linkOutput, sectionName).getSection();
              ElfSection strings =
                  elf.getMandatorySectionByName(linkOutput, stringSectionName).getSection();
              ElfSymbolTable table = ElfSymbolTable.parse(elf.header.ei_class, section.body);

              ImmutableList.Builder<ElfSymbolTable.Entry> fixedEntries = ImmutableList.builder();
              RichStream.from(table.entries)
                  .map(
                      entry ->
                          new ElfSymbolTable.Entry(
                              entry.st_name,
                              fixInfoField(strings, entry.st_name, entry.st_info),
                              fixOtherField(strings, entry.st_name, entry.st_other),
                              entry.st_shndx,
                              entry.st_value,
                              entry.st_size))
                  .forEach(fixedEntries::add);
              ElfSymbolTable fixedUpTable = new ElfSymbolTable(fixedEntries.build());
              Preconditions.checkState(table.entries.size() == fixedUpTable.entries.size());
              section.body.rewind();
              fixedUpTable.write(elf.header.ei_class, section.body);
            }

            private ElfSymbolTable.Entry.Info fixInfoField(
                ElfSection strings, long st_name, ElfSymbolTable.Entry.Info st_info) {
              if (symbolsToLocalize.contains(strings.lookupString(st_name))) {
                // Change binding to local.
                return new ElfSymbolTable.Entry.Info(
                    ElfSymbolTable.Entry.Info.Bind.STB_LOCAL, st_info.st_type);
              }
              return st_info;
            }

            private int fixOtherField(ElfSection strings, long st_name, int st_other) {
              if (symbolsToLocalize.contains(strings.lookupString(st_name))) {
                // Change visibility to hidden.
                return (st_other & ~0x3) | 2;
              }
              return st_other;
            }

            @Override
            public String getShortName() {
              return "localize_dynamic_symbols";
            }

            @Override
            public String getDescription(StepExecutionContext context) {
              return String.format(
                  "localize_dynamic_symbols --symbols %s --in %s --out %s",
                  symbolsToLocalize, linkOutput, finalOutput);
            }
          });
    }
  }
}
