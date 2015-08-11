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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.java.JavaNativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.List;
import java.util.Map;

public class AndroidNativeLibsPackageableGraphEnhancer {

  private static final Flavor COPY_NATIVE_LIBS_FLAVOR = ImmutableFlavor.of("copy_native_libs");

  private final BuildTarget originalBuildTarget;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters;

  /**
   * Maps a {@link NdkCxxPlatforms.TargetCpuType} to the {@link CxxPlatform} we need to use to build C/C++
   * libraries for it.
   */
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;

  public AndroidNativeLibsPackageableGraphEnhancer(
      BuildRuleResolver ruleResolver,
      BuildRuleParams originalParams,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters) {
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.pathResolver = new SourcePathResolver(ruleResolver);
    this.buildRuleParams = originalParams;
    this.ruleResolver = ruleResolver;
    this.nativePlatforms = nativePlatforms;
    this.cpuFilters = cpuFilters;
  }

  // Populates an immutable map builder with all given linkables set to the given cpu type.
  // Returns true iff linkables is not empty.
  private boolean populateMapWithLinkables(
      List<JavaNativeLinkable> linkables,
      ImmutableMap.Builder<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> builder,
      NdkCxxPlatforms.TargetCpuType targetCpuType,
      TargetGraph targetGraph,
      NdkCxxPlatform platform) {

    boolean hasNativeLibs = false;

    for (JavaNativeLinkable nativeLinkable : linkables) {
      ImmutableMap<String, SourcePath> solibs = nativeLinkable.getSharedLibraries(
          targetGraph,
          platform.getCxxPlatform());
      for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
        builder.put(
            new Pair<>(targetCpuType, entry.getKey()),
            entry.getValue());
        hasNativeLibs = true;
      }
    }
    return hasNativeLibs;
  }

  public Optional<CopyNativeLibraries> getCopyNativeLibraries(
      TargetGraph targetGraph,
      AndroidPackageableCollection packageableCollection) {
    // Iterate over all the {@link AndroidNativeLinkable}s from the collector and grab the shared
    // libraries for all the {@link TargetCpuType}s that we care about.  We deposit them into a map
    // of CPU type and SONAME to the shared library path, which the {@link CopyNativeLibraries}
    // rule will use to compose the destination name.
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
      NdkCxxPlatform platform = Preconditions.checkNotNull(nativePlatforms.get(targetCpuType));

      // Populate nativeLinkableLibs and nativeLinkableLibsAssets with the appropriate entries.
      boolean hasNativeLibs = populateMapWithLinkables(
          packageableCollection.getNativeLinkables(),
          nativeLinkableLibsBuilder,
          targetCpuType,
          targetGraph,
          platform);
      boolean hasNativeLibsAssets = populateMapWithLinkables(
          packageableCollection.getNativeLinkablesAssets(),
          nativeLinkableLibsAssetsBuilder,
          targetCpuType,
          targetGraph,
          platform);

      // If we're using a C/C++ runtime other than the system one, add it to the APK.
      NdkCxxPlatforms.CxxRuntime cxxRuntime = platform.getCxxRuntime();
      if ((hasNativeLibs || hasNativeLibsAssets) &&
          !cxxRuntime.equals(NdkCxxPlatforms.CxxRuntime.SYSTEM)) {
        nativeLinkableLibsBuilder.put(
            new Pair<>(
                targetCpuType,
                cxxRuntime.getSoname()),
            new PathSourcePath(
                buildRuleParams.getProjectFilesystem(),
                platform.getCxxSharedRuntimePath()));
      }
    }
    ImmutableMap<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> nativeLinkableLibs =
        nativeLinkableLibsBuilder.build();

    ImmutableMap<Pair<NdkCxxPlatforms.TargetCpuType, String>, SourcePath> nativeLinkableLibsAssets =
        nativeLinkableLibsAssetsBuilder.build();

    if (packageableCollection.getNativeLibsDirectories().isEmpty() &&
        nativeLinkableLibs.isEmpty() &&
        nativeLinkableLibsAssets.isEmpty()) {
      return Optional.absent();
    }

    BuildTarget targetForCopyNativeLibraries = BuildTarget.builder(originalBuildTarget)
        .addFlavors(COPY_NATIVE_LIBS_FLAVOR)
        .build();
    ImmutableSortedSet<BuildRule> nativeLibsRules = BuildRules.toBuildRulesFor(
        originalBuildTarget,
        ruleResolver,
        packageableCollection.getNativeLibsTargets());
    BuildRuleParams paramsForCopyNativeLibraries = buildRuleParams.copyWithChanges(
        targetForCopyNativeLibraries,
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(nativeLibsRules)
                .addAll(
                    pathResolver.filterBuildRuleInputs(
                        packageableCollection.getNativeLibsDirectories()))
                .addAll(pathResolver.filterBuildRuleInputs(nativeLinkableLibs.values()))
                .addAll(pathResolver.filterBuildRuleInputs(nativeLinkableLibsAssets.values()))
                .build()),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    return Optional.of(
        new CopyNativeLibraries(
            paramsForCopyNativeLibraries,
            pathResolver,
            packageableCollection.getNativeLibsDirectories(),
            cpuFilters,
            nativePlatforms,
            nativeLinkableLibs,
            nativeLinkableLibsAssets));
  }
}
