/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.facebook.buck.android.AndroidBinaryResourcesGraphEnhancer.PACKAGE_STRING_ASSETS_FLAVOR;

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

public class AndroidBinaryDescription
    implements Description<AndroidBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidBinaryDescription.AbstractAndroidBinaryDescriptionArg> {

  private static final Logger LOG = Logger.get(AndroidBinaryDescription.class);

  private static final String SECTION = "android";
  private static final String CONFIG_PARAM_REDEX = "redex";

  /**
   * By default, assume we have 5MB of linear alloc, 1MB of which is taken up by the framework, so
   * that leaves 4MB.
   */
  private static final long DEFAULT_LINEAR_ALLOC_HARD_LIMIT = 4 * 1024 * 1024;

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of(
              "exe", new ExecutableMacroExpander(),
              "location", new LocationMacroExpander()));

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  private static final ImmutableSet<Flavor> FLAVORS =
      ImmutableSet.of(
          PACKAGE_STRING_ASSETS_FLAVOR, AndroidBinaryResourcesGraphEnhancer.AAPT2_LINK_FLAVOR);

  private final JavaBuckConfig javaBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions javacOptions;
  private final ProGuardConfig proGuardConfig;
  private final BuckConfig buckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DxConfig dxConfig;
  private final ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private final ListeningExecutorService dxExecutorService;

  public AndroidBinaryDescription(
      JavaBuckConfig javaBuckConfig,
      JavaOptions javaOptions,
      JavacOptions javacOptions,
      ProGuardConfig proGuardConfig,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ListeningExecutorService dxExecutorService,
      BuckConfig buckConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaOptions = javaOptions;
    this.javacOptions = javacOptions;
    this.proGuardConfig = proGuardConfig;
    this.buckConfig = buckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativePlatforms = nativePlatforms;
    this.dxExecutorService = dxExecutorService;
    this.dxConfig = dxConfig;
  }

  @Override
  public Class<AndroidBinaryDescriptionArg> getConstructorArgType() {
    return AndroidBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidBinaryDescriptionArg args)
      throws NoSuchBuildTargetException {
    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(
            Optional.ofNullable(resolver.getEventBus()),
            PerfEventId.of("AndroidBinaryDescription"),
            "target",
            params.getBuildTarget().toString())) {

      ResourceCompressionMode compressionMode = getCompressionMode(args);

      BuildTarget target = params.getBuildTarget();
      boolean isFlavored = target.isFlavored();
      if (isFlavored) {
        if (target.getFlavors().contains(PACKAGE_STRING_ASSETS_FLAVOR)
            && !compressionMode.isStoreStringsAsAssets()) {
          throw new HumanReadableException(
              "'package_string_assets' flavor does not exist for %s.",
              target.getUnflavoredBuildTarget());
        }
        params = params.withBuildTarget(BuildTarget.of(target.getUnflavoredBuildTarget()));
      }

      BuildRule keystore = resolver.getRule(args.getKeystore());
      if (!(keystore instanceof Keystore)) {
        throw new HumanReadableException(
            "In %s, keystore='%s' must be a keystore() but was %s().",
            params.getBuildTarget(), keystore.getFullyQualifiedName(), keystore.getType());
      }

      APKModuleGraph apkModuleGraph = null;
      if (!args.getApplicationModuleConfigs().isEmpty()) {
        apkModuleGraph =
            new APKModuleGraph(
                Optional.of(args.getApplicationModuleConfigs()), targetGraph, target);
      } else {
        apkModuleGraph =
            new APKModuleGraph(
                targetGraph, target, Optional.of(args.getApplicationModuleTargets()));
      }

      ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
          args.getAndroidSdkProguardConfig().orElse(ProGuardObfuscateStep.SdkProguardType.DEFAULT);

      // If the old boolean version of this argument was specified, make sure the new form
      // was not specified, and allow the old form to override the default.
      if (args.getUseAndroidProguardConfigWithOptimizations().isPresent()) {
        Preconditions.checkArgument(
            !args.getAndroidSdkProguardConfig().isPresent(),
            "The deprecated use_android_proguard_config_with_optimizations parameter"
                + " cannot be used with android_sdk_proguard_config.");
        LOG.error(
            "Target %s specified use_android_proguard_config_with_optimizations, "
                + "which is deprecated. Use android_sdk_proguard_config.",
            params.getBuildTarget());
        androidSdkProguardConfig =
            args.getUseAndroidProguardConfigWithOptimizations().orElse(false)
                ? ProGuardObfuscateStep.SdkProguardType.OPTIMIZED
                : ProGuardObfuscateStep.SdkProguardType.DEFAULT;
      }

      EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
      if (!args.getExopackageModes().isEmpty()) {
        exopackageModes = EnumSet.copyOf(args.getExopackageModes());
      } else if (args.isExopackage().orElse(false)) {
        LOG.error(
            "Target %s specified exopackage=True, which is deprecated. Use exopackage_modes.",
            params.getBuildTarget());
        exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
      }

      DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

      PackageType packageType = getPackageType(args);
      boolean shouldPreDex =
          !args.getDisablePreDex()
              && PackageType.DEBUG.equals(packageType)
              && !args.getPreprocessJavaClassesBash().isPresent();

      ResourceFilter resourceFilter = new ResourceFilter(args.getResourceFilter());

      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      AndroidBinaryGraphEnhancer graphEnhancer =
          new AndroidBinaryGraphEnhancer(
              params,
              targetGraph,
              resolver,
              cellRoots,
              args.getAaptMode(),
              compressionMode,
              resourceFilter,
              args.getEffectiveBannedDuplicateResourceTypes(),
              args.getResourceUnionPackage(),
              addFallbackLocales(args.getLocales()),
              args.getManifest(),
              packageType,
              ImmutableSet.copyOf(args.getCpuFilters()),
              args.isBuildStringSourceMap(),
              shouldPreDex,
              AndroidBinary.getPrimaryDexPath(
                  params.getBuildTarget(), params.getProjectFilesystem()),
              dexSplitMode,
              args.getNoDx(),
              /* resourcesToExclude */ ImmutableSet.of(),
              args.isSkipCrunchPngs(),
              args.isIncludesVectorDrawables(),
              javaBuckConfig,
              JavacFactory.create(ruleFinder, javaBuckConfig, null),
              javacOptions,
              exopackageModes,
              args.getBuildConfigValues(),
              args.getBuildConfigValuesFile(),
              Optional.empty(),
              args.isTrimResourceIds(),
              args.getKeepResourcePattern(),
              nativePlatforms,
              Optional.of(args.getNativeLibraryMergeMap()),
              args.getNativeLibraryMergeGlue(),
              args.getNativeLibraryMergeCodeGenerator(),
              args.getNativeLibraryMergeLocalizedSymbols(),
              args.getNativeLibraryProguardConfigGenerator(),
              args.isEnableRelinker() ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
              dxExecutorService,
              args.getManifestEntries(),
              cxxBuckConfig,
              apkModuleGraph,
              dxConfig,
              getPostFilterResourcesArgs(args, params, resolver, cellRoots));
      AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

      if (target.getFlavors().contains(PACKAGE_STRING_ASSETS_FLAVOR)) {
        Optional<PackageStringAssets> packageStringAssets = result.getPackageStringAssets();
        Preconditions.checkState(packageStringAssets.isPresent());
        return packageStringAssets.get();
      }

      if (target.getFlavors().contains(AndroidBinaryResourcesGraphEnhancer.AAPT2_LINK_FLAVOR)) {
        // Rule already added to index during graph enhancement.
        return resolver.getRule(target);
      }

      // Build rules added to "no_dx" are only hints, not hard dependencies. Therefore, although a
      // target may be mentioned in that parameter, it may not be present as a build rule.
      ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
      for (BuildTarget noDxTarget : args.getNoDx()) {
        Optional<BuildRule> ruleOptional = resolver.getRuleOptional(noDxTarget);
        if (ruleOptional.isPresent()) {
          builder.add(ruleOptional.get());
        } else {
          LOG.info("%s: no_dx target not a dependency: %s", target, noDxTarget);
        }
      }

      ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = builder.build();
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
          RichStream.from(buildRulesToExcludeFromDex)
              .filter(JavaLibrary.class)
              .collect(MoreCollectors.toImmutableSortedSet(Ordering.natural()));

      Optional<RedexOptions> redexOptions = getRedexOptions(params, resolver, cellRoots, args);

      ImmutableSortedSet<BuildRule> redexExtraDeps =
          redexOptions
              .map(
                  a ->
                      a.getRedexExtraArgs()
                          .stream()
                          .flatMap(arg -> arg.getDeps(ruleFinder).stream())
                          .collect(MoreCollectors.toImmutableSortedSet(Ordering.natural())))
              .orElse(ImmutableSortedSet.of());

      return new AndroidBinary(
          params
              .copyReplacingExtraDeps(Suppliers.ofInstance(result.getFinalDeps()))
              .copyAppendingExtraDeps(
                  ruleFinder.filterBuildRuleInputs(
                      result.getPackageableCollection().getProguardConfigs()))
              .copyAppendingExtraDeps(rulesToExcludeFromDex)
              .copyAppendingExtraDeps(redexExtraDeps),
          ruleFinder,
          proGuardConfig.getProguardJarOverride(),
          proGuardConfig.getProguardMaxHeapSize(),
          Optional.of(args.getProguardJvmArgs()),
          proGuardConfig.getProguardAgentPath(),
          (Keystore) keystore,
          packageType,
          dexSplitMode,
          args.getNoDx(),
          androidSdkProguardConfig,
          args.getOptimizationPasses(),
          args.getProguardConfig(),
          args.isSkipProguard(),
          redexOptions,
          compressionMode,
          args.getCpuFilters(),
          resourceFilter,
          exopackageModes,
          MACRO_HANDLER.getExpander(params.getBuildTarget(), cellRoots, resolver),
          args.getPreprocessJavaClassesBash(),
          rulesToExcludeFromDex,
          result,
          args.isReorderClassesIntraDex(),
          args.getDexReorderToolFile(),
          args.getDexReorderDataDumpFile(),
          args.getXzCompressionLevel(),
          dxExecutorService,
          args.isPackageAssetLibraries(),
          args.isCompressAssetLibraries(),
          args.getManifestEntries(),
          javaOptions.getJavaRuntimeLauncher(),
          dxConfig.getDxMaxHeapSize());
    }
  }

  private DexSplitMode createDexSplitMode(
      AndroidBinaryDescriptionArg args, EnumSet<ExopackageMode> exopackageModes) {
    // Exopackage builds default to JAR, otherwise, default to RAW.
    DexStore defaultDexStore =
        ExopackageMode.enabledForSecondaryDexes(exopackageModes) ? DexStore.JAR : DexStore.RAW;
    DexSplitStrategy dexSplitStrategy =
        args.getMinimizePrimaryDexSize()
            ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
            : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.getUseSplitDex(),
        dexSplitStrategy,
        args.getDexCompression().orElse(defaultDexStore),
        args.getLinearAllocHardLimit(),
        args.getPrimaryDexPatterns(),
        args.getPrimaryDexClassesFile(),
        args.getPrimaryDexScenarioFile(),
        args.isPrimaryDexScenarioOverflowAllowed(),
        args.getSecondaryDexHeadClassesFile(),
        args.getSecondaryDexTailClassesFile());
  }

  private PackageType getPackageType(AndroidBinaryDescriptionArg args) {
    if (!args.getPackageType().isPresent()) {
      return PackageType.DEBUG;
    }
    return PackageType.valueOf(args.getPackageType().get().toUpperCase(Locale.US));
  }

  private ResourceCompressionMode getCompressionMode(AndroidBinaryDescriptionArg args) {
    if (!args.getResourceCompression().isPresent()) {
      return ResourceCompressionMode.DISABLED;
    }
    return ResourceCompressionMode.valueOf(
        args.getResourceCompression().get().toUpperCase(Locale.US));
  }

  private ImmutableSet<String> addFallbackLocales(ImmutableSet<String> locales) {
    ImmutableSet.Builder<String> allLocales = ImmutableSet.builder();
    for (String locale : locales) {
      allLocales.add(locale);
      Matcher matcher = COUNTRY_LOCALE_PATTERN.matcher(locale);
      if (matcher.matches()) {
        allLocales.add(matcher.group(1));
      }
    }
    return allLocales.build();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    for (Flavor flavor : flavors) {
      if (!FLAVORS.contains(flavor)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAndroidBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getRedex()) {
      // If specified, this option may point to either a BuildTarget or a file.
      Optional<BuildTarget> redexTarget =
          buckConfig.getMaybeBuildTarget(SECTION, CONFIG_PARAM_REDEX);
      if (redexTarget.isPresent()) {
        extraDepsBuilder.add(redexTarget.get());
      }

      constructorArg
          .getRedexExtraArgs()
          .forEach(
              a ->
                  addDepsFromParam(
                      buildTarget, cellRoots, a, extraDepsBuilder, targetGraphOnlyDepsBuilder));
    }
  }

  private void addDepsFromParam(
      BuildTarget target,
      CellPathResolver cellNames,
      String paramValue,
      ImmutableCollection.Builder<BuildTarget> buildDefsBuilder,
      ImmutableCollection.Builder<BuildTarget> nonBuildDefsBuilder) {
    try {
      MACRO_HANDLER.extractParseTimeDeps(
          target, cellNames, paramValue, buildDefsBuilder, nonBuildDefsBuilder);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  private Optional<com.facebook.buck.rules.args.Arg> getPostFilterResourcesArgs(
      AndroidBinaryDescriptionArg arg,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots) {
    return arg.getPostFilterResourcesCmd()
        .map(
            MacroArg.toMacroArgFunction(MACRO_HANDLER, params.getBuildTarget(), cellRoots, resolver)
                ::apply);
  }

  private Optional<RedexOptions> getRedexOptions(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidBinaryDescriptionArg arg) {
    boolean redexRequested = arg.getRedex();
    if (!redexRequested) {
      return Optional.empty();
    }

    Optional<Tool> redexBinary = buckConfig.getTool(SECTION, CONFIG_PARAM_REDEX, resolver);
    if (!redexBinary.isPresent()) {
      throw new HumanReadableException(
          "Requested running ReDex for %s but the path to the tool"
              + "has not been specified in the %s.%s .buckconfig section.",
          params.getBuildTarget(), SECTION, CONFIG_PARAM_REDEX);
    }

    java.util.function.Function<String, com.facebook.buck.rules.args.Arg> macroArgFunction =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, params.getBuildTarget(), cellRoots, resolver)
            ::apply;
    List<com.facebook.buck.rules.args.Arg> redexExtraArgs =
        arg.getRedexExtraArgs().stream().map(macroArgFunction).collect(Collectors.toList());

    return Optional.of(
        RedexOptions.builder()
            .setRedex(redexBinary.get())
            .setRedexConfig(arg.getRedexConfig())
            .setRedexExtraArgs(redexExtraArgs)
            .build());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidBinaryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasTests {
    SourcePath getManifest();

    BuildTarget getKeystore();

    Optional<String> getPackageType();

    @Hint(isDep = false)
    ImmutableSet<BuildTarget> getNoDx();

    @Value.Default
    default boolean getUseSplitDex() {
      return false;
    }

    @Value.Default
    default boolean getMinimizePrimaryDexSize() {
      return false;
    }

    @Value.Default
    default boolean getDisablePreDex() {
      return false;
    }

    // TODO(natthu): mark this as deprecated.
    Optional<Boolean> isExopackage();

    Set<ExopackageMode> getExopackageModes();

    Optional<DexStore> getDexCompression();

    Optional<ProGuardObfuscateStep.SdkProguardType> getAndroidSdkProguardConfig();

    Optional<Boolean> getUseAndroidProguardConfigWithOptimizations();

    Optional<Integer> getOptimizationPasses();

    List<String> getProguardJvmArgs();

    Optional<SourcePath> getProguardConfig();

    Optional<String> getResourceCompression();

    @Value.Default
    default boolean isSkipCrunchPngs() {
      return false;
    }

    @Value.Default
    default boolean isIncludesVectorDrawables() {
      return false;
    }

    List<String> getPrimaryDexPatterns();

    Optional<SourcePath> getPrimaryDexClassesFile();

    Optional<SourcePath> getPrimaryDexScenarioFile();

    @Value.Default
    default boolean isPrimaryDexScenarioOverflowAllowed() {
      return false;
    }

    Optional<SourcePath> getSecondaryDexHeadClassesFile();

    Optional<SourcePath> getSecondaryDexTailClassesFile();

    Set<BuildTarget> getApplicationModuleTargets();

    Map<String, List<BuildTarget>> getApplicationModuleConfigs();

    @Value.Default
    default long getLinearAllocHardLimit() {
      return DEFAULT_LINEAR_ALLOC_HARD_LIMIT;
    }

    List<String> getResourceFilter();

    // Do not inspect this, getAllowedDuplicateResourcesTypes, or getBannedDuplicateResourceTypes directly, use
    // getEffectiveBannedDuplicateResourceTypes.
    // Ideally these should be private, but Arg-population doesn't allow that.
    //
    // If set to ALLOW_BY_DEFAULT, bannedDuplicateResourceTypes is used and setting
    // allowedDuplicateResourceTypes is an error.
    //
    // If set to BAN_BY_DEFAULT, allowedDuplicateResourceTypes is used and setting
    // bannedDuplicateResourceTypes is an error.
    // This only exists to enable migration from allowing by default to banning by default.
    @Value.Default
    default DuplicateResourceBehaviour getDuplicateResourceBehavior() {
      return DuplicateResourceBehaviour.ALLOW_BY_DEFAULT;
    }

    Set<RType> getAllowedDuplicateResourceTypes();

    Set<RType> getBannedDuplicateResourceTypes();

    @Value.Default
    default AndroidBinary.AaptMode getAaptMode() {
      return AndroidBinary.AaptMode.AAPT1;
    }

    @Value.Default
    default boolean isTrimResourceIds() {
      return false;
    }

    Optional<String> getKeepResourcePattern();

    Optional<String> getResourceUnionPackage();

    ImmutableSet<String> getLocales();

    @Value.Default
    default boolean isBuildStringSourceMap() {
      return false;
    }

    Set<TargetCpuType> getCpuFilters();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getPreprocessJavaClassesDeps();

    Optional<String> getPreprocessJavaClassesBash();

    @Value.Default
    default boolean isReorderClassesIntraDex() {
      return false;
    }

    Optional<SourcePath> getDexReorderToolFile();

    Optional<SourcePath> getDexReorderDataDumpFile();

    Optional<Integer> getXzCompressionLevel();

    @Value.Default
    default boolean isPackageAssetLibraries() {
      return false;
    }

    @Value.Default
    default boolean isCompressAssetLibraries() {
      return false;
    }

    Map<String, List<Pattern>> getNativeLibraryMergeMap();

    Optional<BuildTarget> getNativeLibraryMergeGlue();

    Optional<BuildTarget> getNativeLibraryMergeCodeGenerator();

    Optional<ImmutableSortedSet<String>> getNativeLibraryMergeLocalizedSymbols();

    Optional<BuildTarget> getNativeLibraryProguardConfigGenerator();

    @Value.Default
    default boolean isEnableRelinker() {
      return false;
    }

    @Value.Default
    default ManifestEntries getManifestEntries() {
      return ManifestEntries.empty();
    }

    @Value.Default
    default BuildConfigFields getBuildConfigValues() {
      return BuildConfigFields.empty();
    }

    @Value.Default
    default boolean getRedex() {
      return false;
    }

    Optional<SourcePath> getRedexConfig();

    ImmutableList<String> getRedexExtraArgs();

    Optional<String> getPostFilterResourcesCmd();

    Optional<SourcePath> getBuildConfigValuesFile();

    @Value.Default
    default boolean isSkipProguard() {
      return false;
    }

    @Value.Derived
    default EnumSet<RType> getEffectiveBannedDuplicateResourceTypes() {
      if (getDuplicateResourceBehavior() == DuplicateResourceBehaviour.ALLOW_BY_DEFAULT) {
        if (!getAllowedDuplicateResourceTypes().isEmpty()) {
          throw new IllegalArgumentException(
              "Cannot set allowed_duplicate_resource_types if "
                  + "duplicate_resource_behaviour is allow_by_default");
        }
        if (!getBannedDuplicateResourceTypes().isEmpty()) {
          return EnumSet.copyOf(getBannedDuplicateResourceTypes());
        } else {
          return EnumSet.noneOf(RType.class);
        }
      } else if (getDuplicateResourceBehavior() == DuplicateResourceBehaviour.BAN_BY_DEFAULT) {
        if (!getBannedDuplicateResourceTypes().isEmpty()) {
          throw new IllegalArgumentException(
              "Cannot set banned_duplicate_resource_types if "
                  + "duplicate_resource_behaviour is ban_by_default");
        }
        if (!getAllowedDuplicateResourceTypes().isEmpty()) {
          return EnumSet.complementOf(EnumSet.copyOf(getAllowedDuplicateResourceTypes()));
        } else {
          return EnumSet.allOf(RType.class);
        }
      } else {
        throw new IllegalArgumentException(
            "Unrecognized duplicate_resource_behavior: " + getDuplicateResourceBehavior());
      }
    }

    enum DuplicateResourceBehaviour {
      ALLOW_BY_DEFAULT,
      BAN_BY_DEFAULT
    }
  }
}
