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
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.relinker.NativeRelinker;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class AndroidNativeLibsPackageableGraphEnhancer {

  private static final String COPY_NATIVE_LIBS = "copy_native_libs";

  private final ToolchainProvider toolchainProvider;
  private final ProjectFilesystem projectFilesystem;
  private final BuildTarget originalBuildTarget;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final CxxBuckConfig cxxBuckConfig;
  private final Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap;
  private final Optional<BuildTarget> nativeLibraryMergeGlue;
  private final Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols;
  private final ImmutableList<Pattern> relinkerWhitelist;
  private final RelinkerMode relinkerMode;
  private final APKModuleGraph apkModuleGraph;

  private final CellPathResolver cellPathResolver;

  public AndroidNativeLibsPackageableGraphEnhancer(
      ToolchainProvider toolchainProvider,
      CellPathResolver cellPathResolver,
      BuildRuleResolver ruleResolver,
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams originalParams,
      ImmutableSet<TargetCpuType> cpuFilters,
      CxxBuckConfig cxxBuckConfig,
      Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      RelinkerMode relinkerMode,
      ImmutableList<Pattern> relinkerWhitelist,
      APKModuleGraph apkModuleGraph) {
    this.toolchainProvider = toolchainProvider;
    this.cellPathResolver = cellPathResolver;
    this.projectFilesystem = projectFilesystem;
    this.originalBuildTarget = originalBuildTarget;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.nativeLibraryMergeLocalizedSymbols = nativeLibraryMergeLocalizedSymbols;
    this.pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    this.buildRuleParams = originalParams;
    this.ruleResolver = ruleResolver;
    this.cpuFilters = cpuFilters;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativeLibraryMergeMap = nativeLibraryMergeMap;
    this.nativeLibraryMergeGlue = nativeLibraryMergeGlue;
    this.relinkerMode = relinkerMode;
    this.relinkerWhitelist = relinkerWhitelist;
    this.apkModuleGraph = apkModuleGraph;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidNativeLibsGraphEnhancementResult {
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> getCopyNativeLibraries();

    Optional<ImmutableSortedSet<SourcePath>> getUnstrippedLibraries();

    Optional<ImmutableSortedMap<String, String>> getSonameMergeMap();

    Optional<ImmutableSortedMap<String, ImmutableSortedSet<String>>> getSharedObjectTargets();
  }

  // Populates an immutable map builder with all given linkables set to the given cpu type.
  // Returns true iff linkables is not empty.
  private boolean populateMapWithLinkables(
      ImmutableMultimap<APKModule, NativeLinkable> linkables,
      ImmutableMap.Builder<AndroidLinkableMetadata, SourcePath> builder,
      Map<AndroidLinkableMetadata, NativeLinkable> nativeLinkableMap,
      TargetCpuType targetCpuType,
      NdkCxxPlatform platform)
      throws HumanReadableException {

    boolean hasNativeLibs = false;

    for (Map.Entry<APKModule, NativeLinkable> linkableEntry : linkables.entries()) {
      NativeLinkable nativeLinkable = linkableEntry.getValue();
      if (nativeLinkable.getPreferredLinkage(platform.getCxxPlatform(), ruleResolver)
          != NativeLinkable.Linkage.STATIC) {
        ImmutableMap<String, SourcePath> solibs =
            nativeLinkable.getSharedLibraries(platform.getCxxPlatform(), ruleResolver);
        for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
          AndroidLinkableMetadata metadata =
              AndroidLinkableMetadata.builder()
                  .setSoName(entry.getKey())
                  .setTargetCpuType(targetCpuType)
                  .setApkModule(linkableEntry.getKey())
                  .build();
          builder.put(metadata, entry.getValue());
          if (nativeLinkableMap.containsKey(metadata)) {
            throw new HumanReadableException(
                "Two libraries in the dependencies have the same output filename: %s:\n"
                    + "Those libraries are  %s and %s",
                metadata.getSoName(), nativeLinkable, nativeLinkableMap.get(metadata));
          }
          nativeLinkableMap.put(metadata, nativeLinkable);
          hasNativeLibs = true;
        }
      }
    }
    return hasNativeLibs;
  }

  public AndroidNativeLibsGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) {
    @SuppressWarnings("PMD.PrematureDeclaration")
    AndroidNativeLibsGraphEnhancementResult.Builder resultBuilder =
        AndroidNativeLibsGraphEnhancementResult.builder();

    ImmutableMultimap<APKModule, NativeLinkable> nativeLinkables =
        packageableCollection.getNativeLinkables();
    ImmutableMultimap<APKModule, NativeLinkable> nativeLinkablesAssets =
        packageableCollection.getNativeLinkablesAssets();

    ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms = ImmutableMap.of();

    if (!nativeLinkables.isEmpty() || !nativeLinkablesAssets.isEmpty()) {
      NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
          toolchainProvider.getByName(
              NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.class);

      nativePlatforms = ndkCxxPlatformsProvider.getNdkCxxPlatforms();

      if (nativePlatforms.isEmpty()) {
        throw new HumanReadableException(
            "No native platforms detected. Probably Android NDK is not configured properly.");
      }
    }

    if (nativeLibraryMergeMap.isPresent() && !nativeLibraryMergeMap.get().isEmpty()) {
      NativeLibraryMergeEnhancementResult enhancement =
          NativeLibraryMergeEnhancer.enhance(
              cellPathResolver,
              cxxBuckConfig,
              ruleResolver,
              pathResolver,
              ruleFinder,
              originalBuildTarget,
              projectFilesystem,
              nativePlatforms,
              nativeLibraryMergeMap.get(),
              nativeLibraryMergeGlue,
              nativeLibraryMergeLocalizedSymbols,
              nativeLinkables,
              nativeLinkablesAssets);
      nativeLinkables = enhancement.getMergedLinkables();
      nativeLinkablesAssets = enhancement.getMergedLinkablesAssets();
      resultBuilder.setSonameMergeMap(enhancement.getSonameMapping());
      resultBuilder.setSharedObjectTargets(enhancement.getSharedObjectTargets());
    }

    // Iterate over all the {@link AndroidNativeLinkable}s from the collector and grab the shared
    // libraries for all the {@link TargetCpuType}s that we care about.  We deposit them into a map
    // of CPU type and SONAME to the shared library path, which the {@link CopyNativeLibraries}
    // rule will use to compose the destination name.
    ImmutableMap.Builder<APKModule, CopyNativeLibraries> moduleMappedCopyNativeLibriesBuilder =
        ImmutableMap.builder();

    boolean hasCopyNativeLibraries = false;

    // Make sure we process the root module last so that we know if any of the module contain
    // libraries that depend on a non-system runtime and add it to the root module if needed.
    ImmutableSet<APKModule> apkModules =
        FluentIterable.from(apkModuleGraph.getAPKModules())
            .filter(input -> !input.isRootModule())
            .append(apkModuleGraph.getRootAPKModule())
            .toSet();

    ImmutableMap.Builder<AndroidLinkableMetadata, SourcePath> nativeLinkableLibsBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<AndroidLinkableMetadata, SourcePath> nativeLinkableLibsAssetsBuilder =
        ImmutableMap.builder();

    Map<AndroidLinkableMetadata, NativeLinkable> nativeLinkableLibsMap = new HashMap<>();
    Map<AndroidLinkableMetadata, NativeLinkable> nativeLinkableLibsAssetsMap = new HashMap<>();

    if (!nativeLinkables.isEmpty() || !nativeLinkablesAssets.isEmpty()) {
      for (TargetCpuType targetCpuType : getFilteredPlatforms(nativePlatforms, cpuFilters)) {
        NdkCxxPlatform platform = nativePlatforms.get(targetCpuType);
        // Populate nativeLinkableLibs and nativeLinkableLibsAssets with the appropriate entries.
        populateMapWithLinkables(
            nativeLinkables,
            nativeLinkableLibsBuilder,
            nativeLinkableLibsMap,
            targetCpuType,
            platform);
        populateMapWithLinkables(
            nativeLinkablesAssets,
            nativeLinkableLibsAssetsBuilder,
            nativeLinkableLibsAssetsMap,
            targetCpuType,
            platform);
      }
    }
    // Adds a cxxruntime linkable to the nativeLinkableLibsBuilder for every platform that needs it.
    ImmutableMap<AndroidLinkableMetadata, SourcePath> nativeLinkableLibsAssets =
        nativeLinkableLibsAssetsBuilder.build();
    addCxxRuntimeLinkables(nativePlatforms, nativeLinkableLibsBuilder, nativeLinkableLibsAssets);

    ImmutableMap<AndroidLinkableMetadata, SourcePath> nativeLinkableLibs =
        nativeLinkableLibsBuilder.build();

    if (relinkerMode == RelinkerMode.ENABLED
        && (!nativeLinkableLibs.isEmpty() || !nativeLinkableLibsAssets.isEmpty())) {
      NativeRelinker relinker =
          new NativeRelinker(
              originalBuildTarget,
              projectFilesystem,
              buildRuleParams.withExtraDeps(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(ruleFinder.filterBuildRuleInputs(nativeLinkableLibs.values()))
                      .addAll(ruleFinder.filterBuildRuleInputs(nativeLinkableLibsAssets.values()))
                      .build()),
              cellPathResolver,
              pathResolver,
              ruleFinder,
              cxxBuckConfig,
              nativePlatforms,
              nativeLinkableLibs,
              nativeLinkableLibsAssets,
              relinkerWhitelist);

      nativeLinkableLibs = relinker.getRelinkedLibs();
      nativeLinkableLibsAssets = relinker.getRelinkedLibsAssets();
      for (BuildRule rule : relinker.getRules()) {
        ruleResolver.addToIndex(rule);
      }
    }

    ImmutableMap<StripLinkable, StrippedObjectDescription> strippedLibsMap =
        generateStripRules(nativePlatforms, nativeLinkableLibs);
    ImmutableMap<StripLinkable, StrippedObjectDescription> strippedLibsAssetsMap =
        generateStripRules(nativePlatforms, nativeLinkableLibsAssets);

    resultBuilder.setUnstrippedLibraries(
        RichStream.from(nativeLinkableLibs.values())
            .concat(nativeLinkableLibsAssets.values().stream())
            .toImmutableSortedSet(Ordering.natural()));

    for (APKModule module : apkModules) {
      ImmutableMap<StripLinkable, StrippedObjectDescription> filteredStrippedLibsMap =
          ImmutableMap.copyOf(
              FluentIterable.from(strippedLibsMap.entrySet())
                  .filter(entry -> module.equals(entry.getValue().getApkModule())));

      ImmutableMap<StripLinkable, StrippedObjectDescription> filteredStrippedLibsAssetsMap =
          ImmutableMap.copyOf(
              FluentIterable.from(strippedLibsAssetsMap.entrySet())
                  .filter(entry -> module.equals(entry.getValue().getApkModule())));

      ImmutableCollection<SourcePath> nativeLibsDirectories =
          packageableCollection.getNativeLibsDirectories().get(module);

      ImmutableCollection<SourcePath> nativeLibsAssetsDirectories =
          packageableCollection.getNativeLibAssetsDirectories().get(module);

      if (filteredStrippedLibsMap.isEmpty()
          && filteredStrippedLibsAssetsMap.isEmpty()
          && nativeLibsDirectories.isEmpty()
          && nativeLibsAssetsDirectories.isEmpty()) {
        continue;
      }

      moduleMappedCopyNativeLibriesBuilder.put(
          module,
          createCopyNativeLibraries(
              module,
              filteredStrippedLibsMap,
              filteredStrippedLibsAssetsMap,
              nativeLibsDirectories,
              nativeLibsAssetsDirectories));
      hasCopyNativeLibraries = true;
    }
    return resultBuilder
        .setCopyNativeLibraries(
            hasCopyNativeLibraries
                ? Optional.of(moduleMappedCopyNativeLibriesBuilder.build())
                : Optional.empty())
        .build();
  }

  private void addCxxRuntimeLinkables(
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableMap.Builder<AndroidLinkableMetadata, SourcePath> nativeLinkableLibsBuilder,
      ImmutableMap<AndroidLinkableMetadata, SourcePath> nativeLinkableLibsAssets) {
    RichStream.from(nativeLinkableLibsBuilder.build().keySet())
        .concat(RichStream.from(nativeLinkableLibsAssets.keySet()))
        .map(AndroidLinkableMetadata::getTargetCpuType)
        .distinct()
        .forEach(
            targetCpuType -> {
              NdkCxxPlatform platform =
                  Preconditions.checkNotNull(nativePlatforms.get(targetCpuType));
              NdkCxxRuntime cxxRuntime = platform.getCxxRuntime();
              if (cxxRuntime.equals(NdkCxxRuntime.SYSTEM)) {
                // The system runtime doesn't need to be packaged with apks.
                return;
              }
              AndroidLinkableMetadata runtimeLinkableMetadata =
                  AndroidLinkableMetadata.builder()
                      .setTargetCpuType(targetCpuType)
                      .setSoName(cxxRuntime.getSoname())
                      .setApkModule(apkModuleGraph.getRootAPKModule())
                      .build();
              nativeLinkableLibsBuilder.put(
                  runtimeLinkableMetadata,
                  PathSourcePath.of(projectFilesystem, platform.getCxxSharedRuntimePath().get()));
            });
  }

  private CopyNativeLibraries createCopyNativeLibraries(
      APKModule module,
      ImmutableMap<StripLinkable, StrippedObjectDescription> filteredStrippedLibsMap,
      ImmutableMap<StripLinkable, StrippedObjectDescription> filteredStrippedLibsAssetsMap,
      ImmutableCollection<SourcePath> nativeLibsDirectories,
      ImmutableCollection<SourcePath> nativeLibAssetsDirectories) {
    return new CopyNativeLibraries(
        originalBuildTarget.withAppendedFlavors(
            InternalFlavor.of(COPY_NATIVE_LIBS + "_" + module.getName())),
        projectFilesystem,
        ruleFinder,
        ImmutableSet.copyOf(filteredStrippedLibsMap.values()),
        ImmutableSet.copyOf(filteredStrippedLibsAssetsMap.values()),
        ImmutableSet.copyOf(nativeLibsDirectories),
        ImmutableSet.copyOf(nativeLibAssetsDirectories),
        cpuFilters,
        module.getName());
  }

  private static Iterable<TargetCpuType> getFilteredPlatforms(
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableSet<TargetCpuType> cpuFilters) {
    // TODO(agallagher): We currently treat an empty set of filters to mean to allow everything.
    // We should fix this by assigning a default list of CPU filters in the descriptions, but
    // until we do, if the set of filters is empty, just build for all available platforms.
    if (cpuFilters.isEmpty()) {
      return nativePlatforms.keySet();
    }
    Set<TargetCpuType> missing = Sets.difference(cpuFilters, nativePlatforms.keySet());
    Preconditions.checkState(
        missing.isEmpty(), "Unknown platform types <" + Joiner.on(",").join(missing) + ">");
    return cpuFilters;
  }

  private ImmutableMap<StripLinkable, StrippedObjectDescription> generateStripRules(
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableMap<AndroidLinkableMetadata, SourcePath> libs) {
    ImmutableMap.Builder<StripLinkable, StrippedObjectDescription> result = ImmutableMap.builder();
    for (Map.Entry<AndroidLinkableMetadata, SourcePath> entry : libs.entrySet()) {
      SourcePath sourcePath = entry.getValue();
      TargetCpuType targetCpuType = entry.getKey().getTargetCpuType();
      APKModule apkModule = entry.getKey().getApkModule();

      NdkCxxPlatform platform = Preconditions.checkNotNull(nativePlatforms.get(targetCpuType));

      // To be safe, default to using the app rule target as the base for the strip rule.
      // This will be used for stripping the C++ runtime.  We could use something more easily
      // shareable (like just using the app's containing directory, or even the repo root),
      // but stripping the C++ runtime is pretty fast, so just keep the safe old behavior for now.
      BuildTarget baseBuildTarget = originalBuildTarget;
      // But if we're stripping a cxx_library, use that library as the base of the target
      // to allow sharing the rule between all apps that depend on it.
      if (sourcePath instanceof BuildTargetSourcePath) {
        baseBuildTarget = ((BuildTargetSourcePath) sourcePath).getTarget();
      }

      String sharedLibrarySoName = entry.getKey().getSoName();
      StripLinkable stripLinkable =
          requireStripLinkable(
              projectFilesystem,
              ruleFinder,
              ruleResolver,
              sourcePath,
              targetCpuType,
              platform,
              baseBuildTarget,
              sharedLibrarySoName);
      result.put(
          stripLinkable,
          StrippedObjectDescription.builder()
              .setSourcePath(stripLinkable.getSourcePathToOutput())
              .setStrippedObjectName(sharedLibrarySoName)
              .setTargetCpuType(targetCpuType)
              .setApkModule(apkModule)
              .build());
    }
    return result.build();
  }

  // Note: this method produces rules that will be shared between multiple apps,
  // so be careful not to let information about this particular app slip into the definitions.
  private static StripLinkable requireStripLinkable(
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver ruleResolver,
      SourcePath sourcePath,
      TargetCpuType targetCpuType,
      NdkCxxPlatform platform,
      BuildTarget baseBuildTarget,
      String sharedLibrarySoName) {
    BuildTarget targetForStripRule =
        baseBuildTarget.withAppendedFlavors(
            InternalFlavor.of("android-strip"),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(sharedLibrarySoName)),
            InternalFlavor.of(Flavor.replaceInvalidCharacters(targetCpuType.name())));

    return (StripLinkable)
        ruleResolver.computeIfAbsent(
            targetForStripRule,
            (buildTarget) ->
                new StripLinkable(
                    targetForStripRule,
                    projectFilesystem,
                    ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(sourcePath)),
                    platform.getCxxPlatform().getStrip(),
                    sourcePath,
                    sharedLibrarySoName));
  }
}
