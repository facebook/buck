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
import com.facebook.buck.android.AndroidPackageableCollection.ResourceDetails;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

public class AndroidBinaryGraphEnhancer {

  private static final Flavor DEX_FLAVOR = new Flavor("dex");
  private static final Flavor DEX_MERGE_FLAVOR = new Flavor("dex_merge");
  private static final Flavor RESOURCES_FILTER_FLAVOR = new Flavor("resources_filter");
  private static final Flavor AAPT_PACKAGE_FLAVOR = new Flavor("aapt_package");
  private static final Flavor CALCULATE_ABI_FLAVOR = new Flavor("calculate_exopackage_abi");
  private static final Flavor PACKAGE_STRING_ASSETS_FLAVOR = new Flavor("package_string_assets");

  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ResourceFilter resourceFilter;
  private final SourcePath manifest;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final boolean shouldBuildStringSourceMap;
  private final boolean shouldPreDex;
  private final Path primaryDexPath;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final JavacOptions javacOptions;
  private final boolean exopackage;
  private final Keystore keystore;
  private final BuildConfigFields buildConfigValues;
  private final Optional<SourcePath> buildConfigValuesFile;

  AndroidBinaryGraphEnhancer(
      BuildRuleParams originalParams,
      BuildRuleResolver ruleResolver,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      SourcePath manifest,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean shouldBuildStringSourceMap,
      boolean shouldPreDex,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      JavacOptions javacOptions,
      boolean exopackage,
      Keystore keystore,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile) {
    this.buildRuleParams = Preconditions.checkNotNull(originalParams);
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
    this.ruleResolver = Preconditions.checkNotNull(ruleResolver);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.resourceFilter = Preconditions.checkNotNull(resourcesFilter);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.cpuFilters = Preconditions.checkNotNull(cpuFilters);
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.shouldPreDex = shouldPreDex;
    this.primaryDexPath = Preconditions.checkNotNull(primaryDexPath);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.buildTargetsToExcludeFromDex = Preconditions.checkNotNull(buildTargetsToExcludeFromDex);
    this.resourcesToExclude = Preconditions.checkNotNull(resourcesToExclude);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.exopackage = exopackage;
    this.keystore = Preconditions.checkNotNull(keystore);
    this.buildConfigValues = Preconditions.checkNotNull(buildConfigValues);
    this.buildConfigValuesFile = Preconditions.checkNotNull(buildConfigValuesFile);
  }

  EnhancementResult createAdditionalBuildables() {
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget,
            buildTargetsToExcludeFromDex,
            resourcesToExclude);
    collector.addPackageables(AndroidPackageableCollector.getPackageableRules(originalDeps));
    AndroidPackageableCollection packageableCollection = collector.build();
    ResourceDetails resourceDetails = packageableCollection.resourceDetails;

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.resourcesWithNonEmptyResDir);

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering =
        resourceFilter.isEnabled() || resourceCompressionMode.isStoreStringsAsAssets();

    if (needsResourceFiltering) {
      BuildRuleParams paramsForResourcesFilter = buildRuleParams.copyWithChanges(
          BuildRuleType.RESOURCES_FILTER,
          createBuildTargetWithFlavor(RESOURCES_FILTER_FLAVOR),
          resourceRules,
          /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
      ResourcesFilter resourcesFilter = new ResourcesFilter(
          paramsForResourcesFilter,
          resourceDetails.resourceDirectories,
          resourceDetails.whitelistedStringDirectories,
          resourceCompressionMode,
          resourceFilter);
      ruleResolver.addToIndex(resourcesFilter);

      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilter);
      resourceRules = ImmutableSortedSet.<BuildRule>of(resourcesFilter);
    } else {
      filteredResourcesProvider =
          new IdentityResourcesProvider(resourceDetails.resourceDirectories);
    }

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    BuildRuleParams paramsForAaptPackageResources = buildRuleParams.copyWithChanges(
        BuildRuleType.AAPT_PACKAGE,
        buildTargetForAapt,
        getAdditionalAaptDeps(resourceRules, packageableCollection),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        paramsForAaptPackageResources,
        manifest,
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.resourcesWithNonEmptyResDir),
        packageableCollection.assetsDirectories,
        packageType,
        cpuFilters,
        javacOptions,
        shouldPreDex,
        shouldBuildStringSourceMap);
    ruleResolver.addToIndex(aaptPackageResources);
    enhancedDeps.add(aaptPackageResources);

    Optional<PackageStringAssets> packageStringAssets = Optional.absent();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildTarget buildTargetForPackageStringAssets =
          createBuildTargetWithFlavor(PACKAGE_STRING_ASSETS_FLAVOR);
      BuildRuleParams paramsForPackageStringAssets = buildRuleParams.copyWithChanges(
          BuildRuleType.PACKAGE_STRING_ASSETS,
          buildTargetForPackageStringAssets,
          ImmutableSortedSet.<BuildRule>of(aaptPackageResources),
          /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
      packageStringAssets = Optional.of(
          new PackageStringAssets(
              paramsForPackageStringAssets,
              filteredResourcesProvider,
              aaptPackageResources));
      ruleResolver.addToIndex(packageStringAssets.get());
      enhancedDeps.add(packageStringAssets.get());
    }

    // TODO(natthu): Try to avoid re-building the collection by passing UberRDotJava directly.
    if (packageableCollection.resourceDetails.hasRDotJavaPackages) {
      collector.addClasspathEntry(
          aaptPackageResources,
          aaptPackageResources.getPathToCompiledRDotJavaFiles());
    }

    // BuildConfig deps should not be added for instrumented APKs because BuildConfig.class has
    // already been added to the APK under test.
    ImmutableList<DexProducedFromJavaLibrary> preDexBuildConfigs;
    ImmutableList<Path> buildConfigJarFiles;
    if (packageType == PackageType.INSTRUMENTED) {
      preDexBuildConfigs = ImmutableList.of();
      buildConfigJarFiles = ImmutableList.of();
    } else {
      ImmutableList.Builder<DexProducedFromJavaLibrary> preDexBuildConfigsBuilder =
          ImmutableList.builder();
      ImmutableList.Builder<Path> buildConfigJarFilesBuilder = ImmutableList.builder();
      addBuildConfigDeps(
          shouldPreDex,
          packageableCollection,
          enhancedDeps,
          preDexBuildConfigsBuilder,
          buildConfigJarFilesBuilder);
      preDexBuildConfigs = preDexBuildConfigsBuilder.build();
      buildConfigJarFiles = buildConfigJarFilesBuilder.build();
    }

    packageableCollection = collector.build();

    Optional<PreDexMerge> preDexMerge = Optional.absent();
    if (shouldPreDex) {
      preDexMerge = Optional.of(createPreDexMergeRule(
              aaptPackageResources,
              preDexBuildConfigs,
              packageableCollection));
      enhancedDeps.add(preDexMerge.get());
    } else {
      enhancedDeps.addAll(getTargetsAsRules(packageableCollection.javaLibrariesToDex));
    }

    ImmutableSortedSet<BuildRule> finalDeps;
    Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi = Optional.absent();
    if (exopackage) {
      BuildRuleParams paramsForComputeExopackageAbi = buildRuleParams.copyWithChanges(
          BuildRuleType.EXOPACKAGE_DEPS_ABI,
          createBuildTargetWithFlavor(CALCULATE_ABI_FLAVOR),
          enhancedDeps.build(),
          /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
      computeExopackageDepsAbi = Optional.of(
          new ComputeExopackageDepsAbi(
              paramsForComputeExopackageAbi,
              packageableCollection,
              aaptPackageResources,
              packageStringAssets,
              preDexMerge,
              keystore));
      ruleResolver.addToIndex(computeExopackageDepsAbi.get());
      finalDeps = ImmutableSortedSet.<BuildRule>of(computeExopackageDepsAbi.get());
    } else {
      finalDeps = enhancedDeps.build();
    }

    return new EnhancementResult(
        filteredResourcesProvider,
        packageableCollection,
        aaptPackageResources,
        packageStringAssets,
        preDexMerge,
        computeExopackageDepsAbi,
        /* classpathEntriesToDex */ ImmutableSet.<Path>builder()
            .addAll(packageableCollection.classpathEntriesToDex)
            .addAll(buildConfigJarFiles)
            .build(),
        finalDeps);
  }

  /**
   * If the user specified any android_build_config() rules, then we must add some build rules to
   * generate the production {@code BuildConfig.class} files and ensure that they are included in
   * the list of {@link AndroidPackageableCollection#classpathEntriesToDex}.
   */
  private void addBuildConfigDeps(
      boolean shouldPreDex,
      AndroidPackageableCollection packageableCollection,
      ImmutableSortedSet.Builder<BuildRule> enhancedDeps,
      ImmutableList.Builder<DexProducedFromJavaLibrary> preDexRules,
      ImmutableList.Builder<Path> buildConfigJarFilesBuilder) {
    BuildConfigFields buildConfigConstants = BuildConfigFields.fromFields(ImmutableList.of(
        new BuildConfigFields.Field(
            "boolean",
            BuildConfigs.DEBUG_CONSTANT,
            String.valueOf(packageType != AndroidBinary.PackageType.RELEASE)),
        new BuildConfigFields.Field(
            "boolean",
            BuildConfigs.IS_EXO_CONSTANT,
            String.valueOf(exopackage))));
    for (Map.Entry<String, BuildConfigFields> entry :
        packageableCollection.buildConfigs.entrySet()) {
      // Merge the user-defined constants with the APK-specific overrides.
      BuildConfigFields totalBuildConfigValues = BuildConfigFields.empty()
          .putAll(entry.getValue())
          .putAll(buildConfigValues)
          .putAll(buildConfigConstants);

      // Each enhanced dep needs a unique build target, so we parameterize the build target by the
      // Java package.
      String javaPackage = entry.getKey();
      Flavor flavor = new Flavor("buildconfig_" + javaPackage.replace('.', '_'));
      BuildRuleParams buildConfigParams = new BuildRuleParams(
          createBuildTargetWithFlavor(flavor),
          /* declaredDeps */ ImmutableSortedSet.<BuildRule>of(),
          /* extraDeps */ ImmutableSortedSet.<BuildRule>of(),
          BuildTargetPattern.PUBLIC,
          buildRuleParams.getProjectFilesystem(),
          buildRuleParams.getRuleKeyBuilderFactory(),
          AndroidBuildConfigDescription.TYPE);
      JavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription.createBuildRule(
          buildConfigParams,
          javaPackage,
          totalBuildConfigValues,
          buildConfigValuesFile,
          /* useConstantExpressions */ true,
          ruleResolver);
      ruleResolver.addToIndex(buildConfigJavaLibrary);

      enhancedDeps.add(buildConfigJavaLibrary);
      Path buildConfigJar = buildConfigJavaLibrary.getPathToOutputFile();
      Preconditions.checkNotNull(
          buildConfigJar,
          "%s must have an output file.",
          buildConfigJavaLibrary);
      buildConfigJarFilesBuilder.add(buildConfigJar);

      if (shouldPreDex) {
        DexProducedFromJavaLibrary buildConfigDex = new DexProducedFromJavaLibrary(
            buildConfigParams.copyWithChanges(
                BuildRuleType.PRE_DEX,
                createBuildTargetWithFlavor(new Flavor("dex_" + flavor.getName())),
                ImmutableSortedSet.<BuildRule>of(buildConfigJavaLibrary),
                /* extraDeps */ ImmutableSortedSet.<BuildRule>of()),
            buildConfigJavaLibrary);
        ruleResolver.addToIndex(buildConfigDex);
        enhancedDeps.add(buildConfigDex);
        preDexRules.add(buildConfigDex);
      }
    }
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   * <p>
   * This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  @VisibleForTesting
  PreDexMerge createPreDexMergeRule(
      AaptPackageResources aaptPackageResources,
      Iterable<DexProducedFromJavaLibrary> preDexRulesNotInThePackageableCollection,
      AndroidPackageableCollection packageableCollection) {
    ImmutableSet.Builder<DexProducedFromJavaLibrary> preDexDeps = ImmutableSet.builder();
    preDexDeps.addAll(preDexRulesNotInThePackageableCollection);
    for (BuildTarget buildTarget : packageableCollection.javaLibrariesToDex) {
      Preconditions.checkState(
          !buildTargetsToExcludeFromDex.contains(buildTarget),
          "JavaLibrary should have been excluded from target to dex: %s", buildTarget);

      BuildRule libraryRule = ruleResolver.getRule(buildTarget);

      // Skip uber R.java since AaptPackageResources takes care of dexing.
      if (libraryRule.equals(aaptPackageResources)) {
        continue;
      }

      Preconditions.checkState(libraryRule instanceof JavaLibrary);
      JavaLibrary javaLibrary = (JavaLibrary) libraryRule;

      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibrary.getPathToOutputFile() == null) {
        continue;
      }

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibrary.getBuildTarget();
      BuildTarget preDexTarget = BuildTarget.builder(originalTarget)
          .addFlavor(DEX_FLAVOR)
          .build();
      Optional<BuildRule> preDexRule = ruleResolver.getRuleOptional(preDexTarget);
      if (preDexRule.isPresent()) {
        preDexDeps.add((DexProducedFromJavaLibrary) preDexRule.get());
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      BuildRuleParams paramsForPreDex = buildRuleParams.copyWithChanges(
          BuildRuleType.PRE_DEX,
          preDexTarget,
          ImmutableSortedSet.of(ruleResolver.getRule(javaLibrary.getBuildTarget())),
          /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
      DexProducedFromJavaLibrary preDex =
          new DexProducedFromJavaLibrary(paramsForPreDex, javaLibrary);
      ruleResolver.addToIndex(preDex);
      preDexDeps.add(preDex);
    }

    ImmutableSet<DexProducedFromJavaLibrary> allPreDexDeps = preDexDeps.build();

    BuildRuleParams paramsForPreDexMerge = buildRuleParams.copyWithChanges(
        BuildRuleType.DEX_MERGE,
        createBuildTargetWithFlavor(DEX_MERGE_FLAVOR),
        getDexMergeDeps(aaptPackageResources, allPreDexDeps),
        /* extraDeps */ ImmutableSortedSet.<BuildRule>of());
    PreDexMerge preDexMerge = new PreDexMerge(
        paramsForPreDexMerge,
        primaryDexPath,
        dexSplitMode,
        allPreDexDeps,
        aaptPackageResources);
    ruleResolver.addToIndex(preDexMerge);

    return preDexMerge;
  }

  static class EnhancementResult {
    private final FilteredResourcesProvider filteredResourcesProvider;
    private final AndroidPackageableCollection packageableCollection;
    private final AaptPackageResources aaptPackageResources;
    private final Optional<PackageStringAssets> packageStringAssets;
    private final Optional<PreDexMerge> preDexMerge;
    private final Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi;

    /**
     * This includes everything from the corresponding
     * {@link AndroidPackageableCollection#classpathEntriesToDex}, and may include additional
     * entries due to {@link AndroidBuildConfig}s.
     */
    private final ImmutableSet<Path> classpathEntriesToDex;

    private final ImmutableSortedSet<BuildRule> finalDeps;

    public EnhancementResult(
        FilteredResourcesProvider filteredResourcesProvider,
        AndroidPackageableCollection packageableCollection,
        AaptPackageResources aaptPackageBuildable,
        Optional<PackageStringAssets> packageStringAssets,
        Optional<PreDexMerge> preDexMerge,
        Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi,
        ImmutableSet<Path> classpathEntriesToDex,
        ImmutableSortedSet<BuildRule> finalDeps) {
      this.filteredResourcesProvider = Preconditions.checkNotNull(filteredResourcesProvider);
      this.packageableCollection = Preconditions.checkNotNull(packageableCollection);
      this.aaptPackageResources = Preconditions.checkNotNull(aaptPackageBuildable);
      this.packageStringAssets = Preconditions.checkNotNull(packageStringAssets);
      this.preDexMerge = Preconditions.checkNotNull(preDexMerge);
      this.computeExopackageDepsAbi = Preconditions.checkNotNull(computeExopackageDepsAbi);
      this.classpathEntriesToDex = Preconditions.checkNotNull(classpathEntriesToDex);
      this.finalDeps = Preconditions.checkNotNull(finalDeps);
    }

    public FilteredResourcesProvider getFilteredResourcesProvider() {
      return filteredResourcesProvider;
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

    public ImmutableSet<Path> getClasspathEntriesToDex() {
      return classpathEntriesToDex;
    }

    public AndroidPackageableCollection getPackageableCollection() {
      return packageableCollection;
    }
  }

  private BuildTarget createBuildTargetWithFlavor(Flavor flavor) {
    return BuildTarget.builder(originalBuildTarget)
        .addFlavor(flavor)
        .build();
  }

  private ImmutableSortedSet<BuildRule> getAdditionalAaptDeps(
      ImmutableSortedSet<BuildRule> resourceRules,
      AndroidPackageableCollection packageableCollection) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resourceRules)
        .addAll(
            getTargetsAsRules(
                FluentIterable.from(
                    packageableCollection.resourceDetails.resourcesWithEmptyResButNonEmptyAssetsDir)
                    .transform(HasBuildTarget.TO_TARGET)
                    .toList()));
    if (manifest instanceof BuildRuleSourcePath) {
      builder.add(((BuildRuleSourcePath) manifest).getRule());
    }
    return builder.build();
  }

  private ImmutableSortedSet<BuildRule> getDexMergeDeps(
      AaptPackageResources aaptPackageResources,
      ImmutableSet<DexProducedFromJavaLibrary> preDexDeps) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    targets.add(aaptPackageResources.getBuildTarget());
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

  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return FluentIterable.from(getTargetsAsRules(targets))
        .transform(new Function<BuildRule, HasAndroidResourceDeps>() {
                     @Override
                     public HasAndroidResourceDeps apply(BuildRule input) {
                       Preconditions.checkState(input instanceof HasAndroidResourceDeps);
                       return (HasAndroidResourceDeps) input;
                     }
                   })
        .toList();
  }
}
