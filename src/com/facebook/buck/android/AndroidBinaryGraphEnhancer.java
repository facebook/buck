/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidBinaryRule.PackageType;
import com.facebook.buck.android.AndroidBinaryRule.TargetCpuType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidBinaryGraphEnhancer {

  private static final String DEX_FLAVOR = "dex";
  private static final String DEX_MERGE_FLAVOR = "dex_merge";
  private static final String RESOURCES_FILTER_FLAVOR = "resources_filter";
  private static final String UBER_R_DOT_JAVA_FLAVOR = "uber_r_dot_java";
  private static final String AAPT_PACKAGE_FLAVOR = "aapt_package";
  private static final String CALCULATE_ABI_FLAVOR = "calculate_exopackage_abi";
  private static final String PACKAGE_STRING_ASSETS_FLAVOR = "package_string_assets";

  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final BuildRuleBuilderParams buildRuleBuilderParams;
  private final BuildRuleResolver ruleResolver;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ResourceFilter resourceFilter;
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final SourcePath manifest;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final boolean shouldBuildStringSourceMap;
  private final boolean shouldPreDex;
  private final Path primaryDexPath;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildRulesToExcludeFromDex;
  private final JavacOptions javacOptions;
  private final boolean exopackage;
  private final Keystore keystore;

  AndroidBinaryGraphEnhancer(
      BuildRuleParams originalParams,
      BuildRuleResolver ruleResolver,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      SourcePath manifest,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean shouldBuildStringSourceMap,
      boolean shouldPreDex,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildRulesToExcludeFromDex,
      JavacOptions javacOptions,
      boolean exopackage,
      Keystore keystore) {
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
    this.buildRuleBuilderParams = new BuildRuleBuilderParams(
        originalParams.getProjectFilesystem(),
        originalParams.getRuleKeyBuilderFactory());

    this.ruleResolver = Preconditions.checkNotNull(ruleResolver);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.resourceFilter = Preconditions.checkNotNull(resourcesFilter);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.cpuFilters = Preconditions.checkNotNull(cpuFilters);
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.shouldPreDex = shouldPreDex;
    this.primaryDexPath = Preconditions.checkNotNull(primaryDexPath);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.buildRulesToExcludeFromDex = Preconditions.checkNotNull(buildRulesToExcludeFromDex);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.exopackage = exopackage;
    this.keystore = Preconditions.checkNotNull(keystore);
  }

  EnhancementResult createAdditionalBuildables() {
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    BuildTarget buildTargetForFilterResources =
        createBuildTargetWithFlavor(RESOURCES_FILTER_FLAVOR);
    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering =
        resourceFilter.isEnabled() || resourceCompressionMode.isStoreStringsAsAssets();

    if (needsResourceFiltering) {
      BuildRule resourcesFilterBuildRule = ruleResolver.buildAndAddToIndex(
          ResourcesFilter
              .newResourcesFilterBuilder(buildRuleBuilderParams)
              .setBuildTarget(buildTargetForFilterResources)
              .setResourceCompressionMode(resourceCompressionMode)
              .setResourceFilter(resourceFilter)
              .setAndroidResourceDepsFinder(androidResourceDepsFinder));
      filteredResourcesProvider = (ResourcesFilter) resourcesFilterBuildRule.getBuildable();
      enhancedDeps.add(resourcesFilterBuildRule);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(androidResourceDepsFinder);
    }

    BuildTarget buildTargetForUberRDotJava = createBuildTargetWithFlavor(UBER_R_DOT_JAVA_FLAVOR);
    UberRDotJava.Builder uberRDotJavaBuilder =
        UberRDotJava
            .newUberRDotJavaBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForUberRDotJava)
            .setFilteredResourcesProvider(filteredResourcesProvider)
            .setAndroidResourceDepsFinder(androidResourceDepsFinder)
            .setJavacOptions(javacOptions)
            .setRDotJavaNeedsDexing(shouldPreDex)
            .setBuildStringSourceMap(shouldBuildStringSourceMap);
    if (needsResourceFiltering) {
      uberRDotJavaBuilder.addDep(buildTargetForFilterResources);
    }
    BuildRule uberRDotJavaBuildRule = ruleResolver.buildAndAddToIndex(uberRDotJavaBuilder);
    UberRDotJava uberRDotJava = (UberRDotJava) uberRDotJavaBuildRule.getBuildable();
    enhancedDeps.add(uberRDotJavaBuildRule);

    Optional<PackageStringAssets> packageStringAssets = Optional.absent();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildTarget buildTargetForPackageStringAssets =
          createBuildTargetWithFlavor(PACKAGE_STRING_ASSETS_FLAVOR);
      BuildRule packageStringAssetsRule = ruleResolver.buildAndAddToIndex(
          PackageStringAssets
              .newBuilder(buildRuleBuilderParams)
              .setBuildTarget(buildTargetForPackageStringAssets)
              .setFilteredResourcesProvider(filteredResourcesProvider)
              .setUberRDotJava(uberRDotJava));
      packageStringAssets =
          Optional.of((PackageStringAssets) packageStringAssetsRule.getBuildable());
      enhancedDeps.add(packageStringAssetsRule);
    }

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    AaptPackageResources.Builder aaptPackageResourcesBuilder =
        AaptPackageResources
            .newAaptPackageResourcesBuildableBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForAapt)
            .setAllParams(manifest,
                filteredResourcesProvider,
                androidResourceDepsFinder,
                packageType,
                cpuFilters);
    if (needsResourceFiltering) {
      aaptPackageResourcesBuilder.addDep(buildTargetForFilterResources);
    }
    BuildRule aaptPackageResourcesBuildRule =
        ruleResolver.buildAndAddToIndex(aaptPackageResourcesBuilder);
    AaptPackageResources aaptPackageResources =
        (AaptPackageResources) aaptPackageResourcesBuildRule.getBuildable();
    enhancedDeps.add(aaptPackageResourcesBuildRule);

    Optional<PreDexMerge> preDexMerge = Optional.absent();
    if (shouldPreDex) {
      BuildRule preDexMergeRule = createPreDexMergeRule(uberRDotJava);
      preDexMerge = Optional.of((PreDexMerge) preDexMergeRule.getBuildable());
      enhancedDeps.add(preDexMergeRule);
    }

    ImmutableSortedSet<BuildRule> finalDeps;
    Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi = Optional.absent();
    if (exopackage) {
      BuildTarget buildTargetForAbiCalculation = createBuildTargetWithFlavor(CALCULATE_ABI_FLAVOR);
      BuildRule computeExopackageDepsAbiRule = ruleResolver.buildAndAddToIndex(
          ComputeExopackageDepsAbi.newBuildableBuilder(
              buildRuleBuilderParams,
              buildTargetForAbiCalculation,
              enhancedDeps.build(),
              androidResourceDepsFinder,
              uberRDotJava,
              aaptPackageResources,
              packageStringAssets,
              preDexMerge,
              keystore));
      computeExopackageDepsAbi = Optional.of(
          (ComputeExopackageDepsAbi) computeExopackageDepsAbiRule.getBuildable());
      finalDeps = ImmutableSortedSet.of(computeExopackageDepsAbiRule);
    } else {
      finalDeps = enhancedDeps.build();
    }

    return new EnhancementResult(
        filteredResourcesProvider,
        uberRDotJava,
        aaptPackageResources,
        packageStringAssets,
        preDexMerge,
        computeExopackageDepsAbi,
        finalDeps);
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   * <p>
   * This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  @VisibleForTesting
  BuildRule createPreDexMergeRule(UberRDotJava uberRDotJava) {
    ImmutableSet.Builder<IntermediateDexRule> preDexDeps = ImmutableSet.builder();
    ImmutableSet<JavaLibrary> transitiveJavaDeps = Classpaths
        .getClasspathEntries(originalDeps).keySet();
    for (JavaLibrary javaLibrary : transitiveJavaDeps) {
      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibrary.getPathToOutputFile() == null) {
        continue;
      }

      // If the rule is in the no_dx list, then do not pre-dex it.
      if (buildRulesToExcludeFromDex.contains(javaLibrary.getBuildTarget())) {
        continue;
      }

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibrary.getBuildTarget();
      BuildTarget preDexTarget = new BuildTarget(originalTarget.getBaseName(),
          originalTarget.getShortName(),
          DEX_FLAVOR);
      IntermediateDexRule preDexRule = (IntermediateDexRule) ruleResolver.get(preDexTarget);
      if (preDexRule != null) {
        preDexDeps.add(preDexRule);
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      IntermediateDexRule preDex = ruleResolver.buildAndAddToIndex(
          IntermediateDexRule
              .newPreDexBuilder(buildRuleBuilderParams)
              .setBuildTarget(preDexTarget)
              .setJavaLibraryToDex(javaLibrary)
              .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));
      preDexDeps.add(preDex);
    }

    ImmutableSet<IntermediateDexRule> allPreDexDeps = preDexDeps.build();

    BuildTarget buildTargetForDexMerge = createBuildTargetWithFlavor(DEX_MERGE_FLAVOR);
    BuildRule preDexMergeBuildRule = ruleResolver.buildAndAddToIndex(
        PreDexMerge
            .newPreDexMergeBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForDexMerge)
            .setPrimaryDexPath(primaryDexPath)
            .setDexSplitMode(dexSplitMode)
            .setPreDexDeps(allPreDexDeps)
            .setUberRDotJava(uberRDotJava));

    return preDexMergeBuildRule;
  }

  static class EnhancementResult {
    private final FilteredResourcesProvider filteredResourcesProvider;
    private final UberRDotJava uberRDotJava;
    private final AaptPackageResources aaptPackageResources;
    private final Optional<PackageStringAssets> packageStringAssets;
    private final Optional<PreDexMerge> preDexMerge;
    private final Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi;
    private final ImmutableSortedSet<BuildRule> finalDeps;

    public EnhancementResult(
        FilteredResourcesProvider filteredResourcesProvider,
        UberRDotJava uberRDotJava,
        AaptPackageResources aaptPackageBuildable,
        Optional<PackageStringAssets> packageStringAssets,
        Optional<PreDexMerge> preDexMerge,
        Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi,
        ImmutableSortedSet<BuildRule> finalDeps) {
      this.filteredResourcesProvider = Preconditions.checkNotNull(filteredResourcesProvider);
      this.uberRDotJava = Preconditions.checkNotNull(uberRDotJava);
      this.aaptPackageResources = Preconditions.checkNotNull(aaptPackageBuildable);
      this.packageStringAssets = Preconditions.checkNotNull(packageStringAssets);
      this.preDexMerge = Preconditions.checkNotNull(preDexMerge);
      this.computeExopackageDepsAbi = Preconditions.checkNotNull(computeExopackageDepsAbi);
      this.finalDeps = Preconditions.checkNotNull(finalDeps);
    }

    public FilteredResourcesProvider getFilteredResourcesProvider() {
      return filteredResourcesProvider;
    }

    public UberRDotJava getUberRDotJava() {
      return uberRDotJava;
    }

    public AaptPackageResources getAaptPackageResources() {
      return aaptPackageResources;
    }

    public Optional<PreDexMerge> getPreDexMerge() {
      return preDexMerge;
    }

    public ImmutableSortedSet<BuildRule> getFinalDeps() {
      return finalDeps;
    }

    public Optional<ComputeExopackageDepsAbi> getComputeExopackageDepsAbi() {
      return computeExopackageDepsAbi;
    }

    public Optional<PackageStringAssets> getPackageStringAssets() {
      return packageStringAssets;
    }
  }

  private BuildTarget createBuildTargetWithFlavor(String flavor) {
    return new BuildTarget(originalBuildTarget.getBaseName(),
        originalBuildTarget.getShortName(),
        flavor);
  }
}
