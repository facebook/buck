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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.NativeLinkableEnhancementResult;
import com.facebook.buck.android.relinker.NativeRelinker;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.stream.RichStream;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public class AndroidNativeLibsPackageableGraphEnhancer {

  private static final String COPY_NATIVE_LIBS = "copy_native_libs";

  private final ToolchainProvider toolchainProvider;
  private final ProjectFilesystem projectFilesystem;
  private final BuildTarget originalBuildTarget;
  private final ActionGraphBuilder graphBuilder;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final CxxBuckConfig cxxBuckConfig;
  private final Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap;
  private final Optional<BuildTarget> nativeLibraryMergeGlue;
  private final Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols;
  private final ImmutableList<Pattern> relinkerWhitelist;
  private final RelinkerMode relinkerMode;
  private final APKModuleGraph apkModuleGraph;
  private final AndroidNativeTargetConfigurationMatcher androidNativeTargetConfigurationMatcher;
  private final CellPathResolver cellPathResolver;

  public AndroidNativeLibsPackageableGraphEnhancer(
      ToolchainProvider toolchainProvider,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSet<TargetCpuType> cpuFilters,
      CxxBuckConfig cxxBuckConfig,
      Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      RelinkerMode relinkerMode,
      ImmutableList<Pattern> relinkerWhitelist,
      APKModuleGraph apkModuleGraph,
      AndroidNativeTargetConfigurationMatcher androidNativeTargetConfigurationMatcher) {
    this.toolchainProvider = toolchainProvider;
    this.cellPathResolver = cellPathResolver;
    this.projectFilesystem = projectFilesystem;
    this.originalBuildTarget = originalBuildTarget;
    this.nativeLibraryMergeLocalizedSymbols = nativeLibraryMergeLocalizedSymbols;
    this.graphBuilder = graphBuilder;
    this.cpuFilters = cpuFilters;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativeLibraryMergeMap = nativeLibraryMergeMap;
    this.nativeLibraryMergeGlue = nativeLibraryMergeGlue;
    this.relinkerMode = relinkerMode;
    this.relinkerWhitelist = relinkerWhitelist;
    this.apkModuleGraph = apkModuleGraph;
    this.androidNativeTargetConfigurationMatcher = androidNativeTargetConfigurationMatcher;
  }

  @BuckStyleValue
  interface AndroidNativeLibsGraphEnhancementResult {
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> getCopyNativeLibraries();

    Optional<ImmutableSortedSet<SourcePath>> getUnstrippedLibraries();

    Optional<ImmutableSortedMap<String, String>> getSonameMergeMap();

    Optional<ImmutableSortedMap<String, ImmutableSortedSet<String>>> getSharedObjectTargets();

    Optional<CopyNativeLibraries> getCopyNativeLibrariesForSystemLibraryLoader();
  }

  // Populates an immutable map builder with all given linkables set to the given cpu type.
  // Returns true iff linkables is not empty.
  private void getNativeLinkableMetadata(
      ImmutableMultimap<APKModule, NativeLinkable> linkables,
      ImmutableMap.Builder<AndroidLinkableMetadata, SourcePath> builder,
      BiConsumer<AndroidLinkableMetadata, BuildTarget> duplicateReporter,
      TargetCpuType targetCpuType)
      throws HumanReadableException {
    for (Map.Entry<APKModule, NativeLinkable> linkableEntry : linkables.entries()) {
      NativeLinkable nativeLinkable = linkableEntry.getValue();
      if (nativeLinkable.getPreferredLinkage() != NativeLinkableGroup.Linkage.STATIC) {
        if (!androidNativeTargetConfigurationMatcher.nativeTargetConfigurationMatchesCpuType(
            linkableEntry.getValue().getBuildTarget(), targetCpuType)) {
          continue;
        }

        getSharedLibrariesAndMetadata(
            targetCpuType,
            nativeLinkable,
            linkableEntry.getKey(),
            (metadata, libraryPath) -> {
              builder.put(metadata, libraryPath);
              duplicateReporter.accept(metadata, nativeLinkable.getBuildTarget());
            });
      }
    }
  }

  private void getSharedLibrariesAndMetadata(
      TargetCpuType targetCpuType,
      NativeLinkable nativeLinkable,
      APKModule apkModule,
      BiConsumer<AndroidLinkableMetadata, SourcePath> consumer) {
    ImmutableMap<String, SourcePath> solibs = nativeLinkable.getSharedLibraries(graphBuilder);
    for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
      AndroidLinkableMetadata metadata =
          ImmutableAndroidLinkableMetadata.of(entry.getKey(), targetCpuType, apkModule);
      consumer.accept(metadata, entry.getValue());
    }
  }

  public AndroidNativeLibsGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) {
    ImmutableMultimap<APKModule, NativeLinkableGroup> nativeLinkableGroups =
        packageableCollection.getNativeLinkables();
    ImmutableMultimap<APKModule, NativeLinkableGroup> nativeLinkableGroupsAssets =
        packageableCollection.getNativeLinkablesAssets();

    // TODO(cjhopman): The linkables handling is much more complex and we probably should split it
    // out into its own function.
    boolean hasLinkables =
        !(nativeLinkableGroups.isEmpty() && nativeLinkableGroupsAssets.isEmpty());
    boolean hasNativeLibDirs =
        !(packageableCollection.getNativeLibsDirectories().isEmpty()
            && packageableCollection.getNativeLibAssetsDirectories().isEmpty()
            && packageableCollection.getNativeLibsDirectoriesForSystemLoader().isEmpty());
    boolean hasNativeCode = hasLinkables || hasNativeLibDirs;

    if (!hasNativeCode) {
      return ImmutableAndroidNativeLibsGraphEnhancementResult.of(
          Optional.empty(),
          Optional.of(ImmutableSortedSet.of()),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms = ImmutableMap.of();

    if (hasLinkables) {
      NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
          toolchainProvider.getByName(
              NdkCxxPlatformsProvider.DEFAULT_NAME,
              originalBuildTarget.getTargetConfiguration(),
              NdkCxxPlatformsProvider.class);

      nativePlatforms = ndkCxxPlatformsProvider.getResolvedNdkCxxPlatforms(graphBuilder);

      if (nativePlatforms.isEmpty()) {
        throw new HumanReadableException(
            "No native platforms detected. Probably Android NDK is not configured properly.");
      }
    }

    Iterable<TargetCpuType> filteredLinkablePlatforms =
        hasLinkables ? getFilteredPlatforms(nativePlatforms, cpuFilters) : ImmutableList.of();

    ImmutableMap.Builder<TargetCpuType, NativeLinkableEnhancementResult> nativeLinkablesBuilder =
        ImmutableMap.builder();

    for (TargetCpuType cpuType : filteredLinkablePlatforms) {
      nativeLinkablesBuilder.put(
          cpuType,
          expandLinkableGroups(
              nativePlatforms.get(cpuType).getCxxPlatform(),
              nativeLinkableGroups,
              nativeLinkableGroupsAssets));
    }

    ImmutableMap<TargetCpuType, NativeLinkableEnhancementResult> nativeLinkables =
        nativeLinkablesBuilder.build();

    ImmutableSortedMap<String, String> sonameMapping = null;
    ImmutableSortedMap<String, ImmutableSortedSet<String>> sharedObjectTargets = null;
    if (nativeLibraryMergeMap.isPresent()
        && !nativeLibraryMergeMap.get().isEmpty()
        && !nativePlatforms.isEmpty()) {
      NativeLibraryMergeEnhancer.NativeLibraryMergeEnhancementResult enhancement =
          NativeLibraryMergeEnhancer.enhance(
              cellPathResolver,
              cxxBuckConfig,
              graphBuilder,
              originalBuildTarget,
              projectFilesystem,
              nativePlatforms,
              nativeLibraryMergeMap.get(),
              nativeLibraryMergeGlue,
              nativeLibraryMergeLocalizedSymbols,
              nativeLinkables);
      nativeLinkables = enhancement.getMergedLinkables();
      sonameMapping = enhancement.getSonameMapping();
      sharedObjectTargets = enhancement.getSharedObjectTargets();
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

    BiConsumer<AndroidLinkableMetadata, BuildTarget> duplicateReporter =
        new BiConsumer<AndroidLinkableMetadata, BuildTarget>() {
          Map<AndroidLinkableMetadata, BuildTarget> nativeLinkableLibsMap = new HashMap<>();

          @Override
          public void accept(AndroidLinkableMetadata metadata, BuildTarget buildTarget) {
            BuildTarget existing = nativeLinkableLibsMap.putIfAbsent(metadata, buildTarget);
            if (existing != null && existing != buildTarget) {
              throw new HumanReadableException(
                  "Two libraries in the dependencies have the same output filename: %s:\n"
                      + "Those libraries are  %s and %s",
                  metadata.getSoName(), buildTarget, existing);
            }
          }
        };

    for (TargetCpuType targetCpuType : filteredLinkablePlatforms) {
      getNativeLinkableMetadata(
          nativeLinkables.get(targetCpuType).getNativeLinkables(),
          nativeLinkableLibsBuilder,
          duplicateReporter,
          targetCpuType);
      getNativeLinkableMetadata(
          nativeLinkables.get(targetCpuType).getNativeLinkableAssets(),
          nativeLinkableLibsAssetsBuilder,
          duplicateReporter,
          targetCpuType);
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
              cellPathResolver,
              graphBuilder.getSourcePathResolver(),
              graphBuilder,
              cxxBuckConfig,
              nativePlatforms,
              nativeLinkableLibs,
              nativeLinkableLibsAssets,
              relinkerWhitelist);

      nativeLinkableLibs = relinker.getRelinkedLibs();
      nativeLinkableLibsAssets = relinker.getRelinkedLibsAssets();
      for (BuildRule rule : relinker.getRules()) {
        graphBuilder.addToIndex(rule);
      }
    }

    ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription> strippedLibsMap =
        generateStripRules(nativePlatforms, nativeLinkableLibs);
    ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
        strippedLibsAssetsMap = generateStripRules(nativePlatforms, nativeLinkableLibsAssets);

    ImmutableSortedSet<SourcePath> unstrippedLibraries =
        RichStream.from(nativeLinkableLibs.values())
            .concat(nativeLinkableLibsAssets.values().stream())
            .toImmutableSortedSet(Ordering.natural());

    Optional<CopyNativeLibraries> nativeLibrariesForPrimaryApk = Optional.empty();

    for (APKModule module : apkModules) {
      ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
          filteredStrippedLibsMap =
              ImmutableMap.copyOf(
                  FluentIterable.from(strippedLibsMap.entrySet())
                      .filter(entry -> module.equals(entry.getValue().getApkModule())));

      ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
          filteredStrippedLibsAssetsMap =
              ImmutableMap.copyOf(
                  FluentIterable.from(strippedLibsAssetsMap.entrySet())
                      .filter(entry -> module.equals(entry.getValue().getApkModule())));

      ImmutableCollection<SourcePath> nativeLibsDirectories =
          packageableCollection.getNativeLibsDirectories().get(module);

      ImmutableCollection<SourcePath> nativeLibsAssetsDirectories =
          packageableCollection.getNativeLibAssetsDirectories().get(module);

      // Handle native libs which need to go into the primary APK and skip exopackage/optimization
      ImmutableCollection<SourcePath> nativeLibsDirectoriesForPrimaryDexModule =
          module.isRootModule()
              ? packageableCollection.getNativeLibsDirectoriesForSystemLoader()
              : ImmutableList.of();
      if (module.isRootModule() && !nativeLibsDirectoriesForPrimaryDexModule.isEmpty()) {
        nativeLibrariesForPrimaryApk =
            Optional.of(
                createCopyNativeLibraries(
                    module,
                    ImmutableMap.of(),
                    ImmutableMap.of(),
                    nativeLibsDirectoriesForPrimaryDexModule,
                    ImmutableList.of(),
                    "_for_primary_apk"));
      }

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
              nativeLibsAssetsDirectories,
              ""));
      hasCopyNativeLibraries = true;
    }

    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        hasCopyNativeLibraries
            ? Optional.of(moduleMappedCopyNativeLibriesBuilder.build())
            : Optional.empty();

    return ImmutableAndroidNativeLibsGraphEnhancementResult.of(
        copyNativeLibraries,
        Optional.of(unstrippedLibraries),
        Optional.ofNullable(sonameMapping),
        Optional.ofNullable(sharedObjectTargets),
        nativeLibrariesForPrimaryApk);
  }

  private NativeLinkableEnhancementResult expandLinkableGroups(
      CxxPlatform cxxPlatform,
      ImmutableMultimap<APKModule, NativeLinkableGroup> nativeLinkableGroups,
      ImmutableMultimap<APKModule, NativeLinkableGroup> nativeLinkableGroupssAssets) {

    ImmutableMultimap.Builder<APKModule, NativeLinkable> linkablesBuilder =
        ImmutableMultimap.builder();
    ImmutableMultimap.Builder<APKModule, NativeLinkable> linkableAssetsBuilder =
        ImmutableMultimap.builder();

    nativeLinkableGroups.forEach(
        (module, group) ->
            linkablesBuilder.put(module, group.getNativeLinkable(cxxPlatform, graphBuilder)));

    nativeLinkableGroupssAssets.forEach(
        (module, group) ->
            linkableAssetsBuilder.put(module, group.getNativeLinkable(cxxPlatform, graphBuilder)));

    return NativeLinkableEnhancementResult.of(
        linkablesBuilder.build(), linkableAssetsBuilder.build());
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
              NdkCxxPlatform platform = Objects.requireNonNull(nativePlatforms.get(targetCpuType));
              NdkCxxRuntime cxxRuntime = platform.getCxxRuntime();
              Optional<SourcePath> cxxSharedRuntimePath = platform.getCxxSharedRuntimePath();
              if (!cxxSharedRuntimePath.isPresent()) {
                // Not all ndk cxx platforms require a packages c++ runtime.
                return;
              }
              AndroidLinkableMetadata runtimeLinkableMetadata =
                  ImmutableAndroidLinkableMetadata.of(
                      cxxRuntime.getSoname(), targetCpuType, apkModuleGraph.getRootAPKModule());
              nativeLinkableLibsBuilder.put(runtimeLinkableMetadata, cxxSharedRuntimePath.get());
            });
  }

  private CopyNativeLibraries createCopyNativeLibraries(
      APKModule module,
      ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
          filteredStrippedLibsMap,
      ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
          filteredStrippedLibsAssetsMap,
      ImmutableCollection<SourcePath> nativeLibsDirectories,
      ImmutableCollection<SourcePath> nativeLibAssetsDirectories,
      String suffix) {
    return new CopyNativeLibraries(
        originalBuildTarget.withAppendedFlavors(
            InternalFlavor.of(COPY_NATIVE_LIBS + "_" + module.getName() + suffix)),
        projectFilesystem,
        graphBuilder,
        ImmutableSet.copyOf(filteredStrippedLibsMap.values()),
        ImmutableSet.copyOf(filteredStrippedLibsAssetsMap.values()),
        ImmutableSet.copyOf(nativeLibsDirectories),
        ImmutableSet.copyOf(nativeLibAssetsDirectories),
        cpuFilters,
        module.getName());
  }

  private static Iterable<TargetCpuType> getFilteredPlatforms(
      ImmutableMap<TargetCpuType, ?> nativePlatforms, ImmutableSet<TargetCpuType> cpuFilters) {
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

  private ImmutableMap<StripLinkable, CopyNativeLibraries.StrippedObjectDescription>
      generateStripRules(
          ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
          ImmutableMap<AndroidLinkableMetadata, SourcePath> libs) {
    ImmutableMap.Builder<StripLinkable, CopyNativeLibraries.StrippedObjectDescription> result =
        ImmutableMap.builder();
    for (Map.Entry<AndroidLinkableMetadata, SourcePath> entry : libs.entrySet()) {
      SourcePath sourcePath = entry.getValue();
      TargetCpuType targetCpuType = entry.getKey().getTargetCpuType();
      APKModule apkModule = entry.getKey().getApkModule();

      NdkCxxPlatform platform = Objects.requireNonNull(nativePlatforms.get(targetCpuType));

      // To be safe, default to using the app rule target as the base for the strip rule.
      // This will be used for stripping the C++ runtime.  We could use something more easily
      // shareable (like just using the app's containing directory, or even the repo root),
      // but stripping the C++ runtime is pretty fast, so just keep the safe old behavior for now.
      BuildTarget baseBuildTarget = originalBuildTarget;
      ProjectFilesystem filesystem = this.projectFilesystem;
      // But if we're stripping a cxx_library, use that library as the base of the target
      // to allow sharing the rule between all apps that depend on it.
      if (sourcePath instanceof BuildTargetSourcePath) {
        BuildTargetSourcePath targetSourcePath = (BuildTargetSourcePath) sourcePath;
        baseBuildTarget = targetSourcePath.getTarget();
        filesystem = graphBuilder.getRule(targetSourcePath).getProjectFilesystem();
      }

      String sharedLibrarySoName = entry.getKey().getSoName();
      StripLinkable stripLinkable =
          requireStripLinkable(
              filesystem,
              graphBuilder,
              sourcePath,
              targetCpuType,
              platform,
              baseBuildTarget,
              sharedLibrarySoName);
      result.put(
          stripLinkable,
          ImmutableStrippedObjectDescription.of(
              stripLinkable.getSourcePathToOutput(),
              sharedLibrarySoName,
              targetCpuType,
              apkModule));
    }
    return result.build();
  }

  // Note: this method produces rules that will be shared between multiple apps,
  // so be careful not to let information about this particular app slip into the definitions.
  private static StripLinkable requireStripLinkable(
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
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
        graphBuilder.computeIfAbsent(
            targetForStripRule,
            (buildTarget) ->
                new StripLinkable(
                    targetForStripRule,
                    projectFilesystem,
                    graphBuilder,
                    platform.getCxxPlatform().getStrip(),
                    sourcePath,
                    sharedLibrarySoName));
  }
}
