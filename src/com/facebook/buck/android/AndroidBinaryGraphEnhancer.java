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

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaNativeLinkable;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

public class AndroidBinaryGraphEnhancer {

  private static final Flavor COPY_NATIVE_LIBS_FLAVOR = ImmutableFlavor.of("copy_native_libs");
  private static final Flavor DEX_FLAVOR = ImmutableFlavor.of("dex");
  private static final Flavor DEX_MERGE_FLAVOR = ImmutableFlavor.of("dex_merge");
  private static final Flavor RESOURCES_FILTER_FLAVOR = ImmutableFlavor.of("resources_filter");
  private static final Flavor AAPT_PACKAGE_FLAVOR = ImmutableFlavor.of("aapt_package");
  private static final Flavor CALCULATE_ABI_FLAVOR = ImmutableFlavor.of("calculate_exopackage_abi");
  public static final Flavor PACKAGE_STRING_ASSETS_FLAVOR =
      ImmutableFlavor.of("package_string_assets");

  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ResourceFilter resourceFilter;
  private final ImmutableSet<String> locales;
  private final SourcePath manifest;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final boolean shouldBuildStringSourceMap;
  private final boolean shouldPreDex;
  private final Path primaryDexPath;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final boolean skipCrunchPngs;
  private final JavacOptions javacOptions;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final Keystore keystore;
  private final BuildConfigFields buildConfigValues;
  private final Optional<SourcePath> buildConfigValuesFile;
  private final Optional<Integer> xzCompressionLevel;

  /**
   * Maps a {@link TargetCpuType} to the {@link CxxPlatform} we need to use to build C/C++
   * libraries for it.
   */
  private final ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;

  private final ListeningExecutorService dxExecutorService;

  AndroidBinaryGraphEnhancer(
      BuildRuleParams originalParams,
      BuildRuleResolver ruleResolver,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      ImmutableSet<String> locales,
      SourcePath manifest,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean shouldBuildStringSourceMap,
      boolean shouldPreDex,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      boolean skipCrunchPngs,
      JavacOptions javacOptions,
      EnumSet<ExopackageMode> exopackageModes,
      Keystore keystore,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      Optional<Integer> xzCompressionLevel,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ListeningExecutorService dxExecutorService) {
    this.buildRuleParams = originalParams;
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
    this.ruleResolver = ruleResolver;
    this.pathResolver = new SourcePathResolver(ruleResolver);
    this.resourceCompressionMode = resourceCompressionMode;
    this.resourceFilter = resourcesFilter;
    this.locales = locales;
    this.manifest = manifest;
    this.packageType = packageType;
    this.cpuFilters = cpuFilters;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.shouldPreDex = shouldPreDex;
    this.primaryDexPath = primaryDexPath;
    this.dexSplitMode = dexSplitMode;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.skipCrunchPngs = skipCrunchPngs;
    this.javacOptions = javacOptions;
    this.exopackageModes = exopackageModes;
    this.keystore = keystore;
    this.buildConfigValues = buildConfigValues;
    this.buildConfigValuesFile = buildConfigValuesFile;
    this.nativePlatforms = nativePlatforms;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
  }

  AndroidGraphEnhancementResult createAdditionalBuildables() {
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget,
            buildTargetsToExcludeFromDex,
            resourcesToExclude);
    collector.addPackageables(AndroidPackageableCollector.getPackageableRules(originalDeps));
    AndroidPackageableCollection packageableCollection = collector.build();
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails();

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering = resourceFilter.isEnabled() ||
        resourceCompressionMode.isStoreStringsAsAssets() ||
        !locales.isEmpty();

    if (needsResourceFiltering) {
      BuildRuleParams paramsForResourcesFilter =
          buildRuleParams.copyWithChanges(
              createBuildTargetWithFlavor(RESOURCES_FILTER_FLAVOR),
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(resourceRules)
                      .addAll(
                          pathResolver.filterBuildRuleInputs(
                              resourceDetails.getResourceDirectories()))
                      .build()),
              /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      ResourcesFilter resourcesFilter = new ResourcesFilter(
          paramsForResourcesFilter,
          pathResolver,
          resourceDetails.getResourceDirectories(),
          ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
          locales,
          resourceCompressionMode,
          resourceFilter);
      ruleResolver.addToIndex(resourcesFilter);

      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilter);
      resourceRules = ImmutableSortedSet.<BuildRule>of(resourcesFilter);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(
          pathResolver.getAllPaths(resourceDetails.getResourceDirectories()));
    }

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    BuildRuleParams paramsForAaptPackageResources = buildRuleParams.copyWithChanges(
        buildTargetForAapt,
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                // Add all deps with non-empty res dirs, since we at least need the R.txt file
                // (even if we're filtering).
                .addAll(getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir()))
                .addAll(
                    pathResolver.filterBuildRuleInputs(resourceDetails.getResourceDirectories()))
                .addAll(getAdditionalAaptDeps(pathResolver, resourceRules, packageableCollection))
                .build()),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        paramsForAaptPackageResources,
        pathResolver,
        manifest,
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        packageableCollection.getAssetsDirectories(),
        packageType,
        javacOptions,
        shouldPreDex,
        shouldBuildStringSourceMap,
        locales.isEmpty(),
        skipCrunchPngs);
    ruleResolver.addToIndex(aaptPackageResources);
    enhancedDeps.add(aaptPackageResources);

    Optional<PackageStringAssets> packageStringAssets = Optional.absent();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildTarget buildTargetForPackageStringAssets =
          createBuildTargetWithFlavor(PACKAGE_STRING_ASSETS_FLAVOR);
      BuildRuleParams paramsForPackageStringAssets = buildRuleParams.copyWithChanges(
          buildTargetForPackageStringAssets,
          Suppliers.ofInstance(
              ImmutableSortedSet.<BuildRule>naturalOrder()
                  .add(aaptPackageResources)
                  // Model the dependency on the presence of res directories, which, in the case
                  // of resource filtering, is cached by the `ResourcesFilter` rule.
                  .addAll(
                      Iterables.filter(
                          ImmutableList.of(filteredResourcesProvider),
                          BuildRule.class))
                  .build()),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      packageStringAssets = Optional.of(
          new PackageStringAssets(
              paramsForPackageStringAssets,
              pathResolver,
              locales,
              filteredResourcesProvider,
              aaptPackageResources));
      ruleResolver.addToIndex(packageStringAssets.get());
      enhancedDeps.add(packageStringAssets.get());
    }

    // TODO(natthu): Try to avoid re-building the collection by passing UberRDotJava directly.
    if (packageableCollection.getResourceDetails().hasResources()) {
      collector.addClasspathEntry(
          aaptPackageResources,
          new BuildTargetSourcePath(
              aaptPackageResources.getBuildTarget(),
              aaptPackageResources.getPathToCompiledRDotJavaFiles()));
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
      enhancedDeps.addAll(getTargetsAsRules(packageableCollection.getJavaLibrariesToDex()));
    }

    // Add dependencies on all the build rules generating third-party JARs.  This is mainly to
    // correctly capture deps when a prebuilt_jar forwards the output from another build rule.
    enhancedDeps.addAll(
        pathResolver.filterBuildRuleInputs(packageableCollection.getPathsToThirdPartyJars()));

    // Iterate over all the {@link AndroidNativeLinkable}s from the collector and grab the shared
    // libraries for all the {@link TargetCpuType}s that we care about.  We deposit them into a map
    // of CPU type and SONAME to the shared library path, which the {@link CopyNativeLibraries}
    // rule will use to compose the destination name.
    ImmutableMap.Builder<Pair<TargetCpuType, String>, SourcePath> nativeLinkableLibsBuilder =
        ImmutableMap.builder();

    // TODO(agallagher): We currently treat an empty set of filters to mean to allow everything.
    // We should fix this by assigning a default list of CPU filters in the descriptions, but
    // until we doIf the set of filters is empty, just build for all available platforms.
    ImmutableSet<TargetCpuType> filters =
        cpuFilters.isEmpty() ? nativePlatforms.keySet() : cpuFilters;
    for (TargetCpuType targetCpuType : filters) {
      NdkCxxPlatform platform = Preconditions.checkNotNull(nativePlatforms.get(targetCpuType));
      boolean hasNativeLibs = false;

      for (JavaNativeLinkable nativeLinkable : packageableCollection.getNativeLinkables()) {
        ImmutableMap<String, SourcePath> solibs = nativeLinkable.getSharedLibraries(
            platform.getCxxPlatform());
        for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
          nativeLinkableLibsBuilder.put(
              new Pair<>(targetCpuType, entry.getKey()),
              entry.getValue());
          hasNativeLibs = true;
        }
      }

      // If we're using a C/C++ runtime other than the system one, add it to the APK.
      NdkCxxPlatforms.CxxRuntime cxxRuntime = platform.getCxxRuntime();
      if (hasNativeLibs && !cxxRuntime.equals(NdkCxxPlatforms.CxxRuntime.SYSTEM)) {
        nativeLinkableLibsBuilder.put(
            new Pair<>(
                targetCpuType,
                cxxRuntime.getSoname()),
            new PathSourcePath(
                buildRuleParams.getProjectFilesystem(),
                platform.getCxxSharedRuntimePath()));
      }
    }
    ImmutableMap<Pair<TargetCpuType, String>, SourcePath> nativeLinkableLibs =
        nativeLinkableLibsBuilder.build();

    Optional<CopyNativeLibraries> copyNativeLibraries = Optional.absent();
    if (!packageableCollection.getNativeLibsDirectories().isEmpty() ||
        !nativeLinkableLibs.isEmpty()) {
      BuildRuleParams paramsForCopyNativeLibraries = buildRuleParams.copyWithChanges(
          createBuildTargetWithFlavor(COPY_NATIVE_LIBS_FLAVOR),
          Suppliers.ofInstance(
              ImmutableSortedSet.<BuildRule>naturalOrder()
                  .addAll(getTargetsAsRules(packageableCollection.getNativeLibsTargets()))
                  .addAll(
                      pathResolver.filterBuildRuleInputs(
                          packageableCollection.getNativeLibsDirectories()))
                  .addAll(pathResolver.filterBuildRuleInputs(nativeLinkableLibs.values()))
                  .build()),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      copyNativeLibraries = Optional.of(
          new CopyNativeLibraries(
              paramsForCopyNativeLibraries,
              pathResolver,
              packageableCollection.getNativeLibsDirectories(),
              cpuFilters,
              nativePlatforms,
              nativeLinkableLibs));
      ruleResolver.addToIndex(copyNativeLibraries.get());
      enhancedDeps.add(copyNativeLibraries.get());
    }

    Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi = Optional.absent();
    if (!exopackageModes.isEmpty()) {
      BuildRuleParams paramsForComputeExopackageAbi = buildRuleParams.copyWithChanges(
          createBuildTargetWithFlavor(CALCULATE_ABI_FLAVOR),
          Suppliers.ofInstance(enhancedDeps.build()),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      computeExopackageDepsAbi = Optional.of(
          new ComputeExopackageDepsAbi(
              paramsForComputeExopackageAbi,
              pathResolver,
              exopackageModes,
              packageableCollection,
              aaptPackageResources,
              copyNativeLibraries,
              packageStringAssets,
              preDexMerge,
              keystore));
      ruleResolver.addToIndex(computeExopackageDepsAbi.get());
      enhancedDeps.add(computeExopackageDepsAbi.get());
    }

    return AndroidGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setAaptPackageResources(aaptPackageResources)
        .setCopyNativeLibraries(copyNativeLibraries)
        .setPackageStringAssets(packageStringAssets)
        .setPreDexMerge(preDexMerge)
        .setComputeExopackageDepsAbi(computeExopackageDepsAbi)
        .setClasspathEntriesToDex(
            ImmutableSet.<Path>builder()
                .addAll(pathResolver.getAllPaths(packageableCollection.getClasspathEntriesToDex()))
                .addAll(buildConfigJarFiles)
                .build())
        .setFinalDeps(enhancedDeps.build())
        .build();
  }

  /**
   * If the user specified any android_build_config() rules, then we must add some build rules to
   * generate the production {@code BuildConfig.class} files and ensure that they are included in
   * the list of {@link AndroidPackageableCollection#getClasspathEntriesToDex}.
   */
  private void addBuildConfigDeps(
      boolean shouldPreDex,
      AndroidPackageableCollection packageableCollection,
      ImmutableSortedSet.Builder<BuildRule> enhancedDeps,
      ImmutableList.Builder<DexProducedFromJavaLibrary> preDexRules,
      ImmutableList.Builder<Path> buildConfigJarFilesBuilder) {
    BuildConfigFields buildConfigConstants = BuildConfigFields.fromFields(
        ImmutableList.<BuildConfigFields.Field>of(
            BuildConfigFields.Field.of(
                "boolean",
                BuildConfigs.DEBUG_CONSTANT,
                String.valueOf(packageType != AndroidBinary.PackageType.RELEASE)),
            BuildConfigFields.Field.of(
                "boolean",
                BuildConfigs.IS_EXO_CONSTANT,
                String.valueOf(!exopackageModes.isEmpty())),
            BuildConfigFields.Field.of(
                "int",
                BuildConfigs.EXOPACKAGE_FLAGS,
                String.valueOf(ExopackageMode.toBitmask(exopackageModes)))));
    for (Map.Entry<String, BuildConfigFields> entry :
        packageableCollection.getBuildConfigs().entrySet()) {
      // Merge the user-defined constants with the APK-specific overrides.
      BuildConfigFields totalBuildConfigValues = BuildConfigFields.empty()
          .putAll(entry.getValue())
          .putAll(buildConfigValues)
          .putAll(buildConfigConstants);

      // Each enhanced dep needs a unique build target, so we parameterize the build target by the
      // Java package.
      String javaPackage = entry.getKey();
      Flavor flavor = ImmutableFlavor.of("buildconfig_" + javaPackage.replace('.', '_'));
      BuildRuleParams buildConfigParams = new BuildRuleParams(
          createBuildTargetWithFlavor(flavor),
          /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
          buildRuleParams.getProjectFilesystem(),
          buildRuleParams.getRuleKeyBuilderFactory(),
          buildRuleParams.getTargetGraph());
      JavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription.createBuildRule(
          buildConfigParams,
          javaPackage,
          totalBuildConfigValues,
          buildConfigValuesFile,
          /* useConstantExpressions */ true,
          javacOptions,
          ruleResolver);
      ruleResolver.addToIndex(buildConfigJavaLibrary);

      enhancedDeps.add(buildConfigJavaLibrary);
      Path buildConfigJar = buildConfigJavaLibrary.getPathToOutput();
      Preconditions.checkNotNull(
          buildConfigJar,
          "%s must have an output file.",
          buildConfigJavaLibrary);
      buildConfigJarFilesBuilder.add(buildConfigJar);

      if (shouldPreDex) {
        DexProducedFromJavaLibrary buildConfigDex = new DexProducedFromJavaLibrary(
            buildConfigParams.copyWithChanges(
                createBuildTargetWithFlavor(ImmutableFlavor.of("dex_" + flavor.getName())),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(buildConfigJavaLibrary)),
                /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
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
    ImmutableSortedSet.Builder<JavaLibrary> javaLibraryDepsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSet.Builder<DexProducedFromJavaLibrary> preDexDeps = ImmutableSet.builder();
    preDexDeps.addAll(preDexRulesNotInThePackageableCollection);
    for (BuildTarget buildTarget : packageableCollection.getJavaLibrariesToDex()) {
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
      if (javaLibrary.getPathToOutput() == null) {
        continue;
      }

      // Take note of the rule so we add it to the enhanced deps.
      javaLibraryDepsBuilder.add(javaLibrary);

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibrary.getBuildTarget();
      BuildTarget preDexTarget = BuildTarget.builder(originalTarget)
          .addFlavors(DEX_FLAVOR)
          .build();
      Optional<BuildRule> preDexRule = ruleResolver.getRuleOptional(preDexTarget);
      if (preDexRule.isPresent()) {
        preDexDeps.add((DexProducedFromJavaLibrary) preDexRule.get());
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      BuildRuleParams paramsForPreDex = buildRuleParams.copyWithChanges(
          preDexTarget,
          Suppliers.ofInstance(
              ImmutableSortedSet.of(ruleResolver.getRule(javaLibrary.getBuildTarget()))),
          /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
      DexProducedFromJavaLibrary preDex =
          new DexProducedFromJavaLibrary(paramsForPreDex, pathResolver, javaLibrary);
      ruleResolver.addToIndex(preDex);
      preDexDeps.add(preDex);
    }

    ImmutableSet<DexProducedFromJavaLibrary> allPreDexDeps = preDexDeps.build();

    BuildRuleParams paramsForPreDexMerge = buildRuleParams.copyWithChanges(
        createBuildTargetWithFlavor(DEX_MERGE_FLAVOR),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(getDexMergeDeps(aaptPackageResources, allPreDexDeps))
                .addAll(javaLibraryDepsBuilder.build())
                .build()),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    PreDexMerge preDexMerge = new PreDexMerge(
        paramsForPreDexMerge,
        pathResolver,
        primaryDexPath,
        dexSplitMode,
        allPreDexDeps,
        aaptPackageResources,
        dxExecutorService,
        xzCompressionLevel);
    ruleResolver.addToIndex(preDexMerge);

    return preDexMerge;
  }

  private BuildTarget createBuildTargetWithFlavor(Flavor flavor) {
    return BuildTarget.builder(originalBuildTarget)
        .addFlavors(flavor)
        .build();
  }

  private ImmutableSortedSet<BuildRule> getAdditionalAaptDeps(
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> resourceRules,
      AndroidPackageableCollection packageableCollection) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resourceRules)
        .addAll(
            getTargetsAsRules(
                FluentIterable.from(
                    packageableCollection.getResourceDetails()
                        .getResourcesWithEmptyResButNonEmptyAssetsDir())
                    .transform(HasBuildTarget.TO_TARGET)
                    .toList()));
    Optional<BuildRule> manifestRule = resolver.getRule(manifest);
    if (manifestRule.isPresent()) {
      builder.add(manifestRule.get());
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
        buildTargets);
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
