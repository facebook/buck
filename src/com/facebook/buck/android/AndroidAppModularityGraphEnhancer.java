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
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

public class AndroidAppModularityGraphEnhancer {

  private final BuildTarget originalBuildTarget;
  private final SortedSet<BuildRule> originalDeps;
  private final ActionGraphBuilder ruleResolver;
  private final ToolchainProvider toolchainProvider;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final boolean shouldIncludeLibraries;
  private final APKModuleGraph apkModuleGraph;
  private final ConfigurationRuleRegistry configurationRuleRegistry;

  AndroidAppModularityGraphEnhancer(
      BuildTarget buildTarget,
      BuildRuleParams originalParams,
      ActionGraphBuilder ruleResolver,
      ToolchainProvider toolchainProvider,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      boolean shouldIncludeLibraries,
      APKModuleGraph apkModuleGraph,
      ConfigurationRuleRegistry configurationRuleRegistry) {
    this.originalBuildTarget = buildTarget;
    this.originalDeps = originalParams.getBuildDeps();
    this.ruleResolver = ruleResolver;
    this.toolchainProvider = toolchainProvider;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.shouldIncludeLibraries = shouldIncludeLibraries;
    this.apkModuleGraph = apkModuleGraph;
    this.configurationRuleRegistry = configurationRuleRegistry;
  }

  private ImmutableMap<TargetCpuType, NdkCxxPlatform> getPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            NdkCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            NdkCxxPlatformsProvider.class)
        .getResolvedNdkCxxPlatforms(ruleResolver);
  }

  private ImmutableMultimap<APKModule, String> buildModuleToSharedLibraries(
      AndroidPackageableCollection collection) {
    ImmutableCollection<NdkCxxPlatform> platforms =
        getPlatforms(originalBuildTarget.getTargetConfiguration()).values();
    ImmutableMultimap.Builder<APKModule, String> builder = ImmutableMultimap.builder();
    for (Map.Entry<APKModule, NativeLinkableGroup> item :
        collection.getNativeLinkablesAssets().entries()) {
      for (NdkCxxPlatform platform : platforms) {
        NativeLinkable link =
            item.getValue().getNativeLinkable(platform.getCxxPlatform(), ruleResolver);
        ImmutableSet<String> soNames = link.getSharedLibraries(ruleResolver).keySet();
        builder.putAll(item.getKey(), soNames);
      }
    }
    return builder.build();
  }

  AndroidAppModularityGraphEnhancementResult createAdditionalBuildables() {

    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget,
            buildTargetsToExcludeFromDex,
            apkModuleGraph,
            AndroidPackageableFilterFactory.createForNonNativeTargets(
                configurationRuleRegistry, originalBuildTarget));
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(originalDeps), ruleResolver);
    AndroidPackageableCollection packageableCollection = collector.build();

    enhancedDeps.addAll(getTargetsAsRules(packageableCollection.getJavaLibrariesToDex()));

    // Add dependencies on all the build rules generating third-party JARs.  This is mainly to
    // correctly capture deps when a prebuilt_jar forwards the output from another build rule.
    enhancedDeps.addAll(
        ruleResolver.filterBuildRuleInputs(
            packageableCollection
                .getPathsToThirdPartyJars()
                .get(apkModuleGraph.getRootAPKModule())));

    Optional<ImmutableMultimap<APKModule, String>> moduleToSharedLibrary =
        shouldIncludeLibraries
            ? Optional.of(buildModuleToSharedLibraries(packageableCollection))
            : Optional.empty();

    return ImmutableAndroidAppModularityGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setFinalDeps(enhancedDeps.build())
        .setAPKModuleGraph(apkModuleGraph)
        .setModulesToSharedLibraries(moduleToSharedLibrary)
        .build();
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(originalBuildTarget, ruleResolver, buildTargets);
  }
}
