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
import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class AndroidBinaryGraphEnhancer {

  public static final Flavor DEX_FLAVOR = InternalFlavor.of("dex");
  public static final Flavor DEX_MERGE_FLAVOR = InternalFlavor.of("dex_merge");
  private static final Flavor CALCULATE_ABI_FLAVOR = InternalFlavor.of("calculate_exopackage_abi");
  private static final Flavor TRIM_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("trim_uber_r_dot_java");
  private static final Flavor COMPILE_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("compile_uber_r_dot_java");
  private static final Flavor DEX_UBER_R_DOT_JAVA_FLAVOR = InternalFlavor.of("dex_uber_r_dot_java");
  private static final Flavor GENERATE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR =
      InternalFlavor.of("generate_native_lib_merge_map_generated_code");
  private static final Flavor COMPILE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR =
      InternalFlavor.of("compile_native_lib_merge_map_generated_code");
  public static final Flavor NATIVE_LIBRARY_PROGUARD_FLAVOR =
      InternalFlavor.of("generate_proguard_config_from_native_libs");

  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final BuildRuleParams buildRuleParams;
  private final boolean trimResourceIds;
  private final Optional<String> keepResourcePattern;
  private final Optional<BuildTarget> nativeLibraryMergeCodeGenerator;
  private final TargetGraph targetGraph;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final CellPathResolver cellRoots;
  private final PackageType packageType;
  private final boolean shouldPreDex;
  private final Path primaryDexPath;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final JavaBuckConfig javaBuckConfig;
  private final Javac javac;
  private final JavacOptions javacOptions;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final BuildConfigFields buildConfigValues;
  private final Optional<SourcePath> buildConfigValuesFile;
  private final Optional<Integer> xzCompressionLevel;
  private final AndroidNativeLibsPackageableGraphEnhancer nativeLibsEnhancer;
  private final APKModuleGraph apkModuleGraph;
  private final Optional<BuildTarget> nativeLibraryProguardConfigGenerator;
  private final ListeningExecutorService dxExecutorService;
  private final DxConfig dxConfig;
  private final AndroidBinaryResourcesGraphEnhancer androidBinaryResourcesGraphEnhancer;

  AndroidBinaryGraphEnhancer(
      BuildRuleParams originalParams,
      TargetGraph targetGraph,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      AndroidBinary.AaptMode aaptMode,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<String> resourceUnionPackage,
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
      boolean includesVectorDrawables,
      JavaBuckConfig javaBuckConfig,
      Javac javac,
      JavacOptions javacOptions,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      Optional<Integer> xzCompressionLevel,
      boolean trimResourceIds,
      Optional<String> keepResourcePattern,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<BuildTarget> nativeLibraryMergeCodeGenerator,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      Optional<BuildTarget> nativeLibraryProguardConfigGenerator,
      RelinkerMode relinkerMode,
      ListeningExecutorService dxExecutorService,
      ManifestEntries manifestEntries,
      CxxBuckConfig cxxBuckConfig,
      APKModuleGraph apkModuleGraph,
      DxConfig dxConfig,
      Optional<Arg> postFilterResourcesCmd) {
    this.buildRuleParams = originalParams;
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getBuildDeps();
    this.targetGraph = targetGraph;
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.cellRoots = cellRoots;
    this.packageType = packageType;
    this.shouldPreDex = shouldPreDex;
    this.primaryDexPath = primaryDexPath;
    this.dexSplitMode = dexSplitMode;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.javaBuckConfig = javaBuckConfig;
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.exopackageModes = exopackageModes;
    this.buildConfigValues = buildConfigValues;
    this.buildConfigValuesFile = buildConfigValuesFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.trimResourceIds = trimResourceIds;
    this.keepResourcePattern = keepResourcePattern;
    this.nativeLibraryMergeCodeGenerator = nativeLibraryMergeCodeGenerator;
    this.nativeLibraryProguardConfigGenerator = nativeLibraryProguardConfigGenerator;
    this.nativeLibsEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            originalParams,
            nativePlatforms,
            cpuFilters,
            cxxBuckConfig,
            nativeLibraryMergeMap,
            nativeLibraryMergeGlue,
            nativeLibraryMergeLocalizedSymbols,
            relinkerMode,
            apkModuleGraph);
    this.androidBinaryResourcesGraphEnhancer =
        new AndroidBinaryResourcesGraphEnhancer(
            buildRuleParams,
            ruleResolver,
            originalBuildTarget,
            ExopackageMode.enabledForResources(exopackageModes),
            manifest,
            aaptMode,
            resourcesFilter,
            resourceCompressionMode,
            locales,
            resourceUnionPackage,
            shouldBuildStringSourceMap,
            skipCrunchPngs,
            includesVectorDrawables,
            bannedDuplicateResourceTypes,
            manifestEntries,
            postFilterResourcesCmd);
    this.apkModuleGraph = apkModuleGraph;
    this.dxConfig = dxConfig;
  }

  AndroidGraphEnhancementResult createAdditionalBuildables() throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    enhancedDeps.addAll(originalDeps);

    ImmutableList.Builder<BuildRule> additionalJavaLibrariesBuilder = ImmutableList.builder();

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget, buildTargetsToExcludeFromDex, resourcesToExclude, apkModuleGraph);
    collector.addPackageables(AndroidPackageableCollector.getPackageableRules(originalDeps));
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableList.Builder<SourcePath> proguardConfigsBuilder = ImmutableList.builder();
    proguardConfigsBuilder.addAll(packageableCollection.getProguardConfigs());

    AndroidNativeLibsGraphEnhancementResult nativeLibsEnhancementResult =
        nativeLibsEnhancer.enhance(packageableCollection);
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        nativeLibsEnhancementResult.getCopyNativeLibraries();
    if (copyNativeLibraries.isPresent()) {
      ruleResolver.addAllToIndex(copyNativeLibraries.get().values());
      enhancedDeps.addAll(copyNativeLibraries.get().values());
    }

    if (nativeLibraryProguardConfigGenerator.isPresent() && packageType.isBuildWithObfuscation()) {
      NativeLibraryProguardGenerator nativeLibraryProguardGenerator =
          createNativeLibraryProguardGenerator(
              copyNativeLibraries
                  .get()
                  .values()
                  .stream()
                  .map(CopyNativeLibraries::getSourcePathToAllLibsDir)
                  .collect(MoreCollectors.toImmutableList()));

      ruleResolver.addToIndex(nativeLibraryProguardGenerator);
      enhancedDeps.add(nativeLibraryProguardGenerator);
      proguardConfigsBuilder.add(nativeLibraryProguardGenerator.getSourcePathToOutput());
    }

    Optional<ImmutableSortedMap<String, String>> sonameMergeMap =
        nativeLibsEnhancementResult.getSonameMergeMap();
    if (sonameMergeMap.isPresent() && nativeLibraryMergeCodeGenerator.isPresent()) {
      BuildRule generatorRule = ruleResolver.getRule(nativeLibraryMergeCodeGenerator.get());

      GenerateCodeForMergedLibraryMap generateCodeForMergedLibraryMap =
          new GenerateCodeForMergedLibraryMap(
              buildRuleParams
                  .withAppendedFlavor(GENERATE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR)
                  .copyReplacingDeclaredAndExtraDeps(
                      Suppliers.ofInstance(ImmutableSortedSet.of(generatorRule)),
                      Suppliers.ofInstance(ImmutableSortedSet.of())),
              sonameMergeMap.get(),
              generatorRule);
      ruleResolver.addToIndex(generateCodeForMergedLibraryMap);

      BuildRuleParams paramsForCompileGenCode =
          buildRuleParams
              .withAppendedFlavor(COMPILE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(ImmutableSortedSet.of(generateCodeForMergedLibraryMap)),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      DefaultJavaLibrary compileMergedNativeLibMapGenCode =
          DefaultJavaLibrary.builder(
                  targetGraph, paramsForCompileGenCode, ruleResolver, cellRoots, javaBuckConfig)
              // Kind of a hack: override language level to 7 to allow string switch.
              // This can be removed once no one who uses this feature sets the level
              // to 6 in their .buckconfig.
              .setJavacOptions(javacOptions.withSourceLevel("7").withTargetLevel("7"))
              .setSrcs(
                  ImmutableSortedSet.of(generateCodeForMergedLibraryMap.getSourcePathToOutput()))
              .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
              .build();
      ruleResolver.addToIndex(compileMergedNativeLibMapGenCode);
      additionalJavaLibrariesBuilder.add(compileMergedNativeLibMapGenCode);
      enhancedDeps.add(compileMergedNativeLibMapGenCode);
    }

    AndroidBinaryResourcesGraphEnhancementResult resourcesEnhancementResult =
        androidBinaryResourcesGraphEnhancer.enhance(packageableCollection);
    enhancedDeps.addAll(resourcesEnhancementResult.getEnhancedDeps());

    // BuildConfig deps should not be added for instrumented APKs because BuildConfig.class has
    // already been added to the APK under test.
    if (packageType != PackageType.INSTRUMENTED) {
      ImmutableSortedSet<JavaLibrary> buildConfigDepsRules =
          addBuildConfigDeps(
              buildRuleParams,
              packageType,
              exopackageModes,
              buildConfigValues,
              buildConfigValuesFile,
              ruleResolver,
              javac,
              javacOptions,
              packageableCollection);
      enhancedDeps.addAll(buildConfigDepsRules);
      additionalJavaLibrariesBuilder.addAll(buildConfigDepsRules);
    }

    ImmutableList<BuildRule> additionalJavaLibraries = additionalJavaLibrariesBuilder.build();
    ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibraries =
        ImmutableMultimap.of();
    if (shouldPreDex) {
      preDexedLibraries =
          createPreDexRulesForLibraries(
              // TODO(dreiss): Put R.java here.
              additionalJavaLibraries, packageableCollection);
    }

    // Create rule to trim uber R.java sources.
    Collection<DexProducedFromJavaLibrary> preDexedLibrariesForResourceIdFiltering =
        trimResourceIds ? preDexedLibraries.values() : ImmutableList.of();
    BuildRuleParams paramsForTrimUberRDotJava =
        buildRuleParams
            .withAppendedFlavor(TRIM_UBER_R_DOT_JAVA_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(
                            ruleFinder.filterBuildRuleInputs(
                                resourcesEnhancementResult.getRDotJavaDir().orElse(null)))
                        .addAll(preDexedLibrariesForResourceIdFiltering)
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
    TrimUberRDotJava trimUberRDotJava =
        new TrimUberRDotJava(
            paramsForTrimUberRDotJava,
            resourcesEnhancementResult.getRDotJavaDir(),
            preDexedLibrariesForResourceIdFiltering,
            keepResourcePattern);
    ruleResolver.addToIndex(trimUberRDotJava);

    // Create rule to compile uber R.java sources.
    BuildRuleParams paramsForCompileUberRDotJava =
        buildRuleParams
            .withAppendedFlavor(COMPILE_UBER_R_DOT_JAVA_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of(trimUberRDotJava)),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
    JavaLibrary compileUberRDotJava =
        DefaultJavaLibrary.builder(
                targetGraph, paramsForCompileUberRDotJava, ruleResolver, cellRoots, javaBuckConfig)
            .setJavacOptions(javacOptions.withSourceLevel("7").withTargetLevel("7"))
            .setSrcs(ImmutableSortedSet.of(trimUberRDotJava.getSourcePathToOutput()))
            .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
            .build();
    ruleResolver.addToIndex(compileUberRDotJava);

    // Create rule to dex uber R.java sources.
    BuildRuleParams paramsForDexUberRDotJava =
        buildRuleParams
            .withAppendedFlavor(DEX_UBER_R_DOT_JAVA_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of(compileUberRDotJava)),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
    DexProducedFromJavaLibrary dexUberRDotJava =
        new DexProducedFromJavaLibrary(paramsForDexUberRDotJava, compileUberRDotJava);
    ruleResolver.addToIndex(dexUberRDotJava);

    Optional<PreDexMerge> preDexMerge = Optional.empty();
    if (shouldPreDex) {
      preDexMerge = Optional.of(createPreDexMergeRule(preDexedLibraries, dexUberRDotJava));
      enhancedDeps.add(preDexMerge.get());
    } else {
      enhancedDeps.addAll(getTargetsAsRules(packageableCollection.getJavaLibrariesToDex()));
      // If not pre-dexing, AndroidBinary needs to ProGuard and/or dex the compiled R.java.
      enhancedDeps.add(compileUberRDotJava);
    }

    // Add dependencies on all the build rules generating third-party JARs.  This is mainly to
    // correctly capture deps when a prebuilt_jar forwards the output from another build rule.
    enhancedDeps.addAll(
        ruleFinder.filterBuildRuleInputs(packageableCollection.getPathsToThirdPartyJars()));

    Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi = Optional.empty();
    if (!exopackageModes.isEmpty()) {
      BuildRuleParams paramsForComputeExopackageAbi =
          buildRuleParams
              .withAppendedFlavor(CALCULATE_ABI_FLAVOR)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(enhancedDeps.build()),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      computeExopackageDepsAbi =
          Optional.of(
              new ComputeExopackageDepsAbi(
                  paramsForComputeExopackageAbi,
                  exopackageModes,
                  packageableCollection,
                  copyNativeLibraries,
                  preDexMerge));
      ruleResolver.addToIndex(computeExopackageDepsAbi.get());
      enhancedDeps.add(computeExopackageDepsAbi.get());
    }

    return AndroidGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setPrimaryResourcesApkPath(resourcesEnhancementResult.getPrimaryResourcesApkPath())
        .setPrimaryApkAssetZips(resourcesEnhancementResult.getPrimaryApkAssetZips())
        .setExoResources(resourcesEnhancementResult.getExoResources())
        .setAndroidManifestPath(resourcesEnhancementResult.getAndroidManifestXml())
        .setSourcePathToAaptGeneratedProguardConfigFile(
            resourcesEnhancementResult.getAaptGeneratedProguardConfigFile())
        .setProguardConfigs(proguardConfigsBuilder.build())
        .setCompiledUberRDotJava(compileUberRDotJava)
        .setCopyNativeLibraries(copyNativeLibraries)
        .setPackageStringAssets(resourcesEnhancementResult.getPackageStringAssets())
        .setPreDexMerge(preDexMerge)
        .setComputeExopackageDepsAbi(computeExopackageDepsAbi)
        .setClasspathEntriesToDex(
            ImmutableSet.<SourcePath>builder()
                .addAll(packageableCollection.getClasspathEntriesToDex())
                .addAll(
                    additionalJavaLibraries
                        .stream()
                        .map(BuildRule::getSourcePathToOutput)
                        .collect(MoreCollectors.toImmutableList()))
                .build())
        .setFinalDeps(enhancedDeps.build())
        .setAPKModuleGraph(apkModuleGraph)
        .build();
  }

  private NativeLibraryProguardGenerator createNativeLibraryProguardGenerator(
      ImmutableList<SourcePath> nativeLibsDirs) throws NoSuchBuildTargetException {
    BuildRuleParams paramsForNativeLibraryProguardGenerator =
        buildRuleParams
            .withAppendedFlavor(NATIVE_LIBRARY_PROGUARD_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(ImmutableSortedSet::of, ImmutableSortedSet::of);

    return new NativeLibraryProguardGenerator(
        paramsForNativeLibraryProguardGenerator,
        ruleFinder,
        nativeLibsDirs,
        ruleResolver.requireRule(nativeLibraryProguardConfigGenerator.get()));
  }

  /**
   * If the user specified any android_build_config() rules, then we must add some build rules to
   * generate the production {@code BuildConfig.class} files and ensure that they are included in
   * the list of {@link AndroidPackageableCollection#getClasspathEntriesToDex}.
   */
  public static ImmutableSortedSet<JavaLibrary> addBuildConfigDeps(
      BuildRuleParams originalParams,
      PackageType packageType,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      BuildRuleResolver ruleResolver,
      Javac javac,
      JavacOptions javacOptions,
      AndroidPackageableCollection packageableCollection)
      throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<JavaLibrary> result = ImmutableSortedSet.naturalOrder();
    BuildConfigFields buildConfigConstants =
        BuildConfigFields.fromFields(
            ImmutableList.of(
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
      BuildConfigFields totalBuildConfigValues =
          BuildConfigFields.empty()
              .putAll(entry.getValue())
              .putAll(buildConfigValues)
              .putAll(buildConfigConstants);

      // Each enhanced dep needs a unique build target, so we parameterize the build target by the
      // Java package.
      String javaPackage = entry.getKey();
      Flavor flavor = InternalFlavor.of("buildconfig_" + javaPackage.replace('.', '_'));
      BuildTarget buildTargetWithFlavors =
          BuildTarget.builder(originalParams.getBuildTarget()).addFlavors(flavor).build();
      BuildRuleParams buildConfigParams =
          new BuildRuleParams(
              buildTargetWithFlavors,
              /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()),
              /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()),
              ImmutableSortedSet.of(),
              originalParams.getProjectFilesystem());
      JavaLibrary buildConfigJavaLibrary =
          AndroidBuildConfigDescription.createBuildRule(
              buildConfigParams,
              javaPackage,
              totalBuildConfigValues,
              buildConfigValuesFile,
              /* useConstantExpressions */ true,
              javac,
              javacOptions,
              ruleResolver);
      ruleResolver.addToIndex(buildConfigJavaLibrary);

      Preconditions.checkNotNull(
          buildConfigJavaLibrary.getSourcePathToOutput(),
          "%s must have an output file.",
          buildConfigJavaLibrary);
      result.add(buildConfigJavaLibrary);
    }
    return result.build();
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   *
   * <p>This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  @VisibleForTesting
  PreDexMerge createPreDexMergeRule(
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> allPreDexDeps,
      DexProducedFromJavaLibrary dexForUberRDotJava) {
    BuildRuleParams paramsForPreDexMerge =
        buildRuleParams
            .withAppendedFlavor(DEX_MERGE_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(
                            getDexMergeDeps(
                                dexForUberRDotJava, ImmutableSet.copyOf(allPreDexDeps.values())))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of()));
    PreDexMerge preDexMerge =
        new PreDexMerge(
            paramsForPreDexMerge,
            primaryDexPath,
            dexSplitMode,
            apkModuleGraph,
            allPreDexDeps,
            dexForUberRDotJava,
            dxExecutorService,
            xzCompressionLevel,
            dxConfig.getDxMaxHeapSize());
    ruleResolver.addToIndex(preDexMerge);

    return preDexMerge;
  }

  @VisibleForTesting
  ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> createPreDexRulesForLibraries(
      Iterable<BuildRule> additionalJavaLibrariesToDex,
      AndroidPackageableCollection packageableCollection) {
    Iterable<BuildTarget> additionalJavaLibraryTargets =
        FluentIterable.from(additionalJavaLibrariesToDex).transform(BuildRule::getBuildTarget);
    ImmutableMultimap.Builder<APKModule, DexProducedFromJavaLibrary> preDexDeps =
        ImmutableMultimap.builder();
    for (BuildTarget buildTarget :
        Iterables.concat(
            packageableCollection.getJavaLibrariesToDex(), additionalJavaLibraryTargets)) {
      Preconditions.checkState(
          !buildTargetsToExcludeFromDex.contains(buildTarget),
          "JavaLibrary should have been excluded from target to dex: %s",
          buildTarget);

      BuildRule libraryRule = ruleResolver.getRule(buildTarget);

      Preconditions.checkState(libraryRule instanceof JavaLibrary);
      JavaLibrary javaLibrary = (JavaLibrary) libraryRule;

      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibrary.getSourcePathToOutput() == null) {
        continue;
      }

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibrary.getBuildTarget();
      BuildTarget preDexTarget = BuildTarget.builder(originalTarget).addFlavors(DEX_FLAVOR).build();
      Optional<BuildRule> preDexRule = ruleResolver.getRuleOptional(preDexTarget);
      if (preDexRule.isPresent()) {
        preDexDeps.put(
            apkModuleGraph.findModuleForTarget(buildTarget),
            (DexProducedFromJavaLibrary) preDexRule.get());
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      BuildRuleParams paramsForPreDex =
          buildRuleParams
              .withBuildTarget(preDexTarget)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(
                      ImmutableSortedSet.of(ruleResolver.getRule(javaLibrary.getBuildTarget()))),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      DexProducedFromJavaLibrary preDex =
          new DexProducedFromJavaLibrary(paramsForPreDex, javaLibrary);
      ruleResolver.addToIndex(preDex);
      preDexDeps.put(apkModuleGraph.findModuleForTarget(buildTarget), preDex);
    }
    return preDexDeps.build();
  }

  private ImmutableSortedSet<BuildRule> getDexMergeDeps(
      DexProducedFromJavaLibrary dexForUberRDotJava,
      ImmutableSet<DexProducedFromJavaLibrary> preDexDeps) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    targets.add(dexForUberRDotJava.getBuildTarget());
    for (DexProducedFromJavaLibrary preDex : preDexDeps) {
      targets.add(preDex.getBuildTarget());
    }
    return getTargetsAsRules(targets.build());
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(originalBuildTarget, ruleResolver, buildTargets);
  }
}
