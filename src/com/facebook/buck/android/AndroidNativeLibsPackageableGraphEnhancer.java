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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.relinker.NativeRelinker;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class AndroidNativeLibsPackageableGraphEnhancer {

  private static final String COPY_NATIVE_LIBS = "copy_native_libs";

  private final BuildTarget originalBuildTarget;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters;
  private final CxxBuckConfig cxxBuckConfig;
  private final Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap;
  private final Optional<BuildTarget> nativeLibraryMergeGlue;
  private final Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols;
  private final RelinkerMode relinkerMode;
  private final APKModuleGraph apkModuleGraph;

  /**
   * Maps a {@link NdkCxxPlatforms.TargetCpuType} to the {@link CxxPlatform} we need to use to build
   * C/C++ libraries for it.
   */
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;

  public AndroidNativeLibsPackageableGraphEnhancer(
      BuildRuleResolver ruleResolver,
      BuildRuleParams originalParams,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters,
      CxxBuckConfig cxxBuckConfig,
      Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      RelinkerMode relinkerMode,
      APKModuleGraph apkModuleGraph) {
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.nativeLibraryMergeLocalizedSymbols = nativeLibraryMergeLocalizedSymbols;
    this.pathResolver = new SourcePathResolver(ruleFinder);
    this.buildRuleParams = originalParams;
    this.ruleResolver = ruleResolver;
    this.nativePlatforms = nativePlatforms;
    this.cpuFilters = cpuFilters;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativeLibraryMergeMap = nativeLibraryMergeMap;
    this.nativeLibraryMergeGlue = nativeLibraryMergeGlue;
    this.relinkerMode = relinkerMode;
    this.apkModuleGraph = apkModuleGraph;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidNativeLibsGraphEnhancementResult {
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> getCopyNativeLibraries();

    Optional<ImmutableSortedMap<String, String>> getSonameMergeMap();
  }

  // Populates an immutable map builder with all given linkables set to the given cpu type.
  // Returns true iff linkables is not empty.
  private boolean populateMapWithLinkables(
      Iterable<NativeLinkable> linkables,
      ImmutableMap.Builder<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> builder,
      NdkCxxPlatforms.TargetCpuType targetCpuType,
      NdkCxxPlatform platform)
      throws NoSuchBuildTargetException {

    boolean hasNativeLibs = false;

    for (NativeLinkable nativeLinkable : linkables) {
      if (nativeLinkable.getPreferredLinkage(platform.getCxxPlatform())
          != NativeLinkable.Linkage.STATIC) {
        ImmutableMap<String, SourcePath> solibs =
            nativeLinkable.getSharedLibraries(platform.getCxxPlatform());
        for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
          builder.put(new Pair<>(targetCpuType, entry.getKey()), entry.getValue());
          hasNativeLibs = true;
        }
      }
    }
    return hasNativeLibs;
  }

  public AndroidNativeLibsGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) throws NoSuchBuildTargetException {
    @SuppressWarnings("PMD.PrematureDeclaration")
    AndroidNativeLibsGraphEnhancementResult.Builder resultBuilder =
        AndroidNativeLibsGraphEnhancementResult.builder();

    ImmutableMultimap<APKModule, NativeLinkable> nativeLinkables =
        packageableCollection.getNativeLinkables();
    ImmutableMultimap<APKModule, NativeLinkable> nativeLinkablesAssets =
        packageableCollection.getNativeLinkablesAssets();

    if (nativeLibraryMergeMap.isPresent() && !nativeLibraryMergeMap.get().isEmpty()) {
      NativeLibraryMergeEnhancementResult enhancement =
          NativeLibraryMergeEnhancer.enhance(
              cxxBuckConfig,
              ruleResolver,
              pathResolver,
              ruleFinder,
              buildRuleParams,
              nativePlatforms,
              nativeLibraryMergeMap.get(),
              nativeLibraryMergeGlue,
              nativeLibraryMergeLocalizedSymbols,
              nativeLinkables,
              nativeLinkablesAssets);
      nativeLinkables = enhancement.getMergedLinkables();
      nativeLinkablesAssets = enhancement.getMergedLinkablesAssets();
      resultBuilder.setSonameMergeMap(enhancement.getSonameMapping());
    }

    // Iterate over all the {@link AndroidNativeLinkable}s from the collector and grab the shared
    // libraries for all the {@link TargetCpuType}s that we care about.  We deposit them into a map
    // of CPU type and SONAME to the shared library path, which the {@link CopyNativeLibraries}
    // rule will use to compose the destination name.
    ImmutableMap.Builder<APKModule, CopyNativeLibraries> moduleMappedCopyNativeLibriesBuilder =
        ImmutableMap.builder();

    boolean hasCopyNativeLibraries = false;
    List<NdkCxxPlatform> platformsWithNativeLibs = new ArrayList<>();
    List<NdkCxxPlatform> platformsWithNativeLibsAssets = new ArrayList<>();

    // Make sure we process the root module last so that we know if any of the module contain
    // libraries that depend on a non-system runtime and add it to the root module if needed.
    ImmutableSet<APKModule> apkModules =
        FluentIterable.from(apkModuleGraph.getAPKModules())
            .filter(input -> !input.isRootModule())
            .append(apkModuleGraph.getRootAPKModule())
            .toSet();

    for (APKModule module : apkModules) {
      ImmutableMap.Builder<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath>
          nativeLinkableLibsBuilder = ImmutableMap.builder();

      ImmutableMap.Builder<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath>
          nativeLinkableLibsAssetsBuilder = ImmutableMap.builder();

      // TODO(agallagher): We currently treat an empty set of filters to mean to allow everything.
      // We should fix this by assigning a default list of CPU filters in the descriptions, but
      // until we do, if the set of filters is empty, just build for all available platforms.
      ImmutableSet<NdkCxxPlatforms.TargetCpuType> filters =
          cpuFilters.isEmpty() ? nativePlatforms.keySet() : cpuFilters;
      for (NdkCxxPlatforms.TargetCpuType targetCpuType : filters) {
        NdkCxxPlatform platform =
            Preconditions.checkNotNull(
                nativePlatforms.get(targetCpuType),
                "Unknown platform type " + targetCpuType.toString());

        // Populate nativeLinkableLibs and nativeLinkableLibsAssets with the appropriate entries.
        if (populateMapWithLinkables(
                nativeLinkables.get(module), nativeLinkableLibsBuilder, targetCpuType, platform)
            && !platformsWithNativeLibs.contains(platform)) {
          platformsWithNativeLibs.add(platform);
        }
        if (populateMapWithLinkables(
                nativeLinkablesAssets.get(module),
                nativeLinkableLibsAssetsBuilder,
                targetCpuType,
                platform)
            && !platformsWithNativeLibsAssets.contains(platform)) {
          platformsWithNativeLibsAssets.add(platform);
        }

        if (module.isRootModule()) {
          // If we're using a C/C++ runtime other than the system one, add it to the APK.
          NdkCxxRuntime cxxRuntime = platform.getCxxRuntime();
          if ((platformsWithNativeLibs.contains(platform)
                  || platformsWithNativeLibsAssets.contains(platform))
              && !cxxRuntime.equals(NdkCxxRuntime.SYSTEM)) {
            nativeLinkableLibsBuilder.put(
                new Pair<>(targetCpuType, cxxRuntime.getSoname()),
                new PathSourcePath(
                    buildRuleParams.getProjectFilesystem(),
                    platform.getCxxSharedRuntimePath().get()));
          }
        }
      }

      ImmutableMap<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> nativeLinkableLibs =
          nativeLinkableLibsBuilder.build();

      ImmutableMap<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath>
          nativeLinkableLibsAssets = nativeLinkableLibsAssetsBuilder.build();

      if (packageableCollection.getNativeLibsDirectories().get(module).isEmpty()
          && nativeLinkableLibs.isEmpty()
          && nativeLinkableLibsAssets.isEmpty()) {
        continue;
      }

      if (relinkerMode == RelinkerMode.ENABLED
          && (!nativeLinkableLibs.isEmpty() || !nativeLinkableLibsAssets.isEmpty())) {
        NativeRelinker relinker =
            new NativeRelinker(
                buildRuleParams.copyReplacingExtraDeps(
                    Suppliers.ofInstance(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(ruleFinder.filterBuildRuleInputs(nativeLinkableLibs.values()))
                            .addAll(
                                ruleFinder.filterBuildRuleInputs(nativeLinkableLibsAssets.values()))
                            .build())),
                pathResolver,
                ruleFinder,
                cxxBuckConfig,
                nativePlatforms,
                nativeLinkableLibs,
                nativeLinkableLibsAssets);

        nativeLinkableLibs = relinker.getRelinkedLibs();
        nativeLinkableLibsAssets = relinker.getRelinkedLibsAssets();
        for (BuildRule rule : relinker.getRules()) {
          ruleResolver.addToIndex(rule);
        }
      }

      ImmutableMap<StripLinkable, StrippedObjectDescription> strippedLibsMap =
          generateStripRules(
              buildRuleParams,
              ruleFinder,
              ruleResolver,
              originalBuildTarget,
              nativePlatforms,
              nativeLinkableLibs);
      ImmutableMap<StripLinkable, StrippedObjectDescription> strippedLibsAssetsMap =
          generateStripRules(
              buildRuleParams,
              ruleFinder,
              ruleResolver,
              originalBuildTarget,
              nativePlatforms,
              nativeLinkableLibsAssets);

      ImmutableSortedSet<BuildRule> nativeLibsRules =
          BuildRules.toBuildRulesFor(
              originalBuildTarget,
              ruleResolver,
              packageableCollection.getNativeLibsTargets().get(module));
      BuildRuleParams paramsForCopyNativeLibraries =
          buildRuleParams
              .withAppendedFlavor(InternalFlavor.of(COPY_NATIVE_LIBS + "_" + module.getName()))
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(nativeLibsRules)
                          .addAll(
                              ruleFinder.filterBuildRuleInputs(
                                  packageableCollection.getNativeLibsDirectories().get(module)))
                          .addAll(strippedLibsMap.keySet())
                          .addAll(strippedLibsAssetsMap.keySet())
                          .build()),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      moduleMappedCopyNativeLibriesBuilder.put(
          module,
          new CopyNativeLibraries(
              paramsForCopyNativeLibraries,
              ImmutableSet.copyOf(packageableCollection.getNativeLibsDirectories().get(module)),
              ImmutableSet.copyOf(strippedLibsMap.values()),
              ImmutableSet.copyOf(strippedLibsAssetsMap.values()),
              cpuFilters,
              module.getName()));
      hasCopyNativeLibraries = true;
    }
    return resultBuilder
        .setCopyNativeLibraries(
            hasCopyNativeLibraries
                ? Optional.of(moduleMappedCopyNativeLibriesBuilder.build())
                : Optional.empty())
        .build();
  }

  // Note: this method produces rules that will be shared between multiple apps,
  // so be careful not to let information about this particular app slip into the definitions.
  private static ImmutableMap<StripLinkable, StrippedObjectDescription> generateStripRules(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver ruleResolver,
      BuildTarget appRuleTarget,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableMap<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> libs) {
    ImmutableMap.Builder<StripLinkable, StrippedObjectDescription> result = ImmutableMap.builder();
    for (Map.Entry<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> entry :
        libs.entrySet()) {
      SourcePath sourcePath = entry.getValue();
      NdkCxxPlatforms.TargetCpuType targetCpuType = entry.getKey().getFirst();

      NdkCxxPlatform platform = Preconditions.checkNotNull(nativePlatforms.get(targetCpuType));

      // To be safe, default to using the app rule target as the base for the strip rule.
      // This will be used for stripping the C++ runtime.  We could use something more easily
      // shareable (like just using the app's containing directory, or even the repo root),
      // but stripping the C++ runtime is pretty fast, so just keep the safe old behavior for now.
      BuildTarget baseBuildTarget = appRuleTarget;
      // But if we're stripping a cxx_library, use that library as the base of the target
      // to allow sharing the rule between all apps that depend on it.
      if (sourcePath instanceof BuildTargetSourcePath) {
        baseBuildTarget = ((BuildTargetSourcePath) sourcePath).getTarget();
      }

      String sharedLibrarySoName = entry.getKey().getSecond();
      BuildTarget targetForStripRule =
          BuildTarget.builder(baseBuildTarget)
              .addFlavors(InternalFlavor.of("android-strip"))
              .addFlavors(InternalFlavor.of(Flavor.replaceInvalidCharacters(sharedLibrarySoName)))
              .addFlavors(InternalFlavor.of(Flavor.replaceInvalidCharacters(targetCpuType.name())))
              .build();

      Optional<BuildRule> previouslyCreated = ruleResolver.getRuleOptional(targetForStripRule);
      StripLinkable stripLinkable;
      if (previouslyCreated.isPresent()) {
        stripLinkable = (StripLinkable) previouslyCreated.get();
      } else {
        BuildRuleParams paramsForStripLinkable =
            buildRuleParams
                .withBuildTarget(targetForStripRule)
                .copyReplacingDeclaredAndExtraDeps(
                    Suppliers.ofInstance(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(ruleFinder.filterBuildRuleInputs(ImmutableList.of(sourcePath)))
                            .build()),
                    Suppliers.ofInstance(ImmutableSortedSet.of()));

        stripLinkable =
            new StripLinkable(
                paramsForStripLinkable,
                platform.getCxxPlatform().getStrip(),
                sourcePath,
                sharedLibrarySoName);

        ruleResolver.addToIndex(stripLinkable);
      }
      result.put(
          stripLinkable,
          StrippedObjectDescription.builder()
              .setSourcePath(stripLinkable.getSourcePathToOutput())
              .setStrippedObjectName(sharedLibrarySoName)
              .setTargetCpuType(targetCpuType)
              .build());
    }
    return result.build();
  }
}
