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

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Buildables;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;

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
  private final BuildRuleParams buildRuleParams;
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
  private final Optional<Path> aaptOverride;

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
      Keystore keystore,
      Optional<Path> aaptOverride) {
    this.buildRuleParams = Preconditions.checkNotNull(originalParams);
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
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
    this.aaptOverride = Preconditions.checkNotNull(aaptOverride);
  }

  EnhancementResult createAdditionalBuildables() {
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    ImmutableSortedSet<BuildRule> resourceRules = getAndroidResourcesAsRules();

    BuildTarget buildTargetForFilterResources =
        createBuildTargetWithFlavor(RESOURCES_FILTER_FLAVOR);
    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering =
        resourceFilter.isEnabled() || resourceCompressionMode.isStoreStringsAsAssets();

    if (needsResourceFiltering) {
      ResourcesFilter resourcesFilter = new ResourcesFilter(
          buildTargetForFilterResources,
          androidResourceDepsFinder,
          resourceCompressionMode,
          resourceFilter);
      BuildRule resourcesFilterBuildRule = buildRuleAndAddToIndex(
          resourcesFilter,
          BuildRuleType.RESOURCES_FILTER,
          buildTargetForFilterResources,
          resourceRules);

      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilterBuildRule);
      resourceRules = ImmutableSortedSet.of(resourcesFilterBuildRule);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(androidResourceDepsFinder);
    }

    BuildTarget buildTargetForUberRDotJava = createBuildTargetWithFlavor(UBER_R_DOT_JAVA_FLAVOR);
    UberRDotJava uberRDotJava = new UberRDotJava(
        buildTargetForUberRDotJava,
        filteredResourcesProvider,
        javacOptions,
        aaptOverride,
        androidResourceDepsFinder,
        shouldPreDex,
        shouldBuildStringSourceMap);
    BuildRule uberRDotJavaBuildRule = buildRuleAndAddToIndex(
        uberRDotJava,
        BuildRuleType.UBER_R_DOT_JAVA,
        buildTargetForUberRDotJava,
        resourceRules);
    enhancedDeps.add(uberRDotJavaBuildRule);

    Optional<PackageStringAssets> packageStringAssets = Optional.absent();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildTarget buildTargetForPackageStringAssets =
          createBuildTargetWithFlavor(PACKAGE_STRING_ASSETS_FLAVOR);
      packageStringAssets = Optional.of(
          new PackageStringAssets(
              buildTargetForPackageStringAssets,
              filteredResourcesProvider,
              uberRDotJava));
      BuildRule packageStringAssetsRule = buildRuleAndAddToIndex(
          packageStringAssets.get(),
          BuildRuleType.PACKAGE_STRING_ASSETS,
          buildTargetForPackageStringAssets,
          ImmutableSortedSet.of(uberRDotJavaBuildRule));
      enhancedDeps.add(packageStringAssetsRule);
    }

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        buildTargetForAapt,
        manifest,
        filteredResourcesProvider,
        androidResourceDepsFinder.getAndroidTransitiveDependencies(),
        packageType,
        cpuFilters,
        aaptOverride);
    BuildRule aaptPackageResourcesBuildRule = buildRuleAndAddToIndex(
        aaptPackageResources,
        BuildRuleType.AAPT_PACKAGE,
        buildTargetForAapt,
        getAdditionalAaptDeps(resourceRules));
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
      computeExopackageDepsAbi = Optional.of(
          new ComputeExopackageDepsAbi(
              buildTargetForAbiCalculation,
              androidResourceDepsFinder,
              uberRDotJava,
              aaptPackageResources,
              packageStringAssets,
              preDexMerge,
              keystore));
      BuildRule computeExopackageDepsAbiRule = buildRuleAndAddToIndex(
          computeExopackageDepsAbi.get(),
          BuildRuleType.EXOPACKAGE_DEPS_ABI,
          buildTargetForAbiCalculation,
          enhancedDeps.build());
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
    ImmutableSet.Builder<DexProducedFromJavaLibrary> preDexDeps = ImmutableSet.builder();
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
      BuildRule preDexRule = ruleResolver.get(preDexTarget);
      if (preDexRule != null) {
        preDexDeps.add((DexProducedFromJavaLibrary) preDexRule.getBuildable());
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      DexProducedFromJavaLibrary preDex = new DexProducedFromJavaLibrary(preDexTarget, javaLibrary);
      buildRuleAndAddToIndex(
          preDex,
          BuildRuleType.PRE_DEX,
          preDexTarget,
          ImmutableSortedSet.of(ruleResolver.get(javaLibrary.getBuildTarget())));
      preDexDeps.add(preDex);
    }

    ImmutableSet<DexProducedFromJavaLibrary> allPreDexDeps = preDexDeps.build();

    BuildTarget buildTargetForDexMerge = createBuildTargetWithFlavor(DEX_MERGE_FLAVOR);
    PreDexMerge preDexMerge = new PreDexMerge(
        buildTargetForDexMerge,
        primaryDexPath,
        dexSplitMode,
        allPreDexDeps,
        uberRDotJava);
    BuildRule preDexMergeBuildRule = buildRuleAndAddToIndex(
        preDexMerge,
        BuildRuleType.DEX_MERGE,
        buildTargetForDexMerge,
        getDexMergeDeps(uberRDotJava, allPreDexDeps));

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

  private ImmutableSortedSet<BuildRule> getAndroidResourcesAsRules() {
    return getTargetsAsRules(
        FluentIterable.from(androidResourceDepsFinder.getAndroidResources())
            .transform(
                new Function<HasAndroidResourceDeps, BuildTarget>() {
                  @Override
                  public BuildTarget apply(HasAndroidResourceDeps input) {
                    return input.getBuildTarget();
                  }
                }
            )
            .toList());
  }

  private ImmutableSortedSet<BuildRule> getAdditionalAaptDeps(
      ImmutableSortedSet<BuildRule> resourceRules) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resourceRules)
        .addAll(getTargetsAsRules(
                FluentIterable.from(androidResourceDepsFinder.getAssetOnlyAndroidResources())
                    .transform(HasBuildTarget.TO_TARGET)
                    .toList()))
        .addAll(getTargetsAsRules(
                androidResourceDepsFinder.getAndroidTransitiveDependencies()
                    .nativeTargetsWithAssets));
    if (manifest instanceof BuildRuleSourcePath) {
      builder.add(((BuildRuleSourcePath) manifest).getRule());
    }
    return builder.build();
  }

  private ImmutableSortedSet<BuildRule> getDexMergeDeps(
      UberRDotJava uberRDotJava,
      ImmutableSet<DexProducedFromJavaLibrary> preDexDeps) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    targets.add(uberRDotJava.getBuildTarget());
    for (DexProducedFromJavaLibrary preDex : preDexDeps) {
      targets.add(preDex.getBuildTarget());
    }
    return getTargetsAsRules(targets.build());
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(
        originalBuildTarget,
        ruleResolver,
        buildTargets,
        /* allowNonExistentRules */ false);
  }

  private BuildRule buildRuleAndAddToIndex(
      Buildable buildable,
      BuildRuleType buildRuleType,
      BuildTarget buildTarget,
      ImmutableSortedSet<BuildRule> deps) {
    BuildRule buildRule = Buildables.createRuleFromBuildable(
        buildable,
        buildRuleType,
        buildTarget,
        deps,
        buildRuleParams);
    ruleResolver.addToIndex(buildTarget, buildRule);
    return buildRule;
  }
}
