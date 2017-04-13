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
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.Hint;
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
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
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

public class AndroidBinaryDescription implements
    Description<AndroidBinaryDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AndroidBinaryDescription.Arg> {

  private static final Logger LOG = Logger.get(AndroidBinaryDescription.class);

  private static final String SECTION = "android";
  private static final String CONFIG_PARAM_REDEX = "redex";

  /**
   * By default, assume we have 5MB of linear alloc,
   * 1MB of which is taken up by the framework, so that leaves 4MB.
   */
  private static final long DEFAULT_LINEAR_ALLOC_HARD_LIMIT = 4 * 1024 * 1024;

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of(
              "exe", new ExecutableMacroExpander(),
              "location", new LocationMacroExpander()));

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  private static final ImmutableSet<Flavor> FLAVORS = ImmutableSet.of(
      PACKAGE_STRING_ASSETS_FLAVOR);

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
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(
        Optional.ofNullable(resolver.getEventBus()),
        PerfEventId.of("AndroidBinaryDescription"),
        "target", params.getBuildTarget().toString())) {

      ResourceCompressionMode compressionMode = getCompressionMode(args);

      BuildTarget target = params.getBuildTarget();
      boolean isFlavored = target.isFlavored();
      if (isFlavored) {
        if (target.getFlavors().contains(PACKAGE_STRING_ASSETS_FLAVOR) &&
            !compressionMode.isStoreStringsAsAssets()) {
          throw new HumanReadableException(
              "'package_string_assets' flavor does not exist for %s.",
              target.getUnflavoredBuildTarget());
        }
        params = params.withBuildTarget(BuildTarget.of(target.getUnflavoredBuildTarget()));
      }

      BuildRule keystore = resolver.getRule(args.keystore);
      if (!(keystore instanceof Keystore)) {
        throw new HumanReadableException(
            "In %s, keystore='%s' must be a keystore() but was %s().",
            params.getBuildTarget(),
            keystore.getFullyQualifiedName(),
            keystore.getType());
      }

      APKModuleGraph apkModuleGraph = new APKModuleGraph(
          targetGraph,
          target,
          Optional.of(args.applicationModuleTargets));

      ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
          args.androidSdkProguardConfig.orElse(ProGuardObfuscateStep.SdkProguardType.DEFAULT);

      // If the old boolean version of this argument was specified, make sure the new form
      // was not specified, and allow the old form to override the default.
      if (args.useAndroidProguardConfigWithOptimizations.isPresent()) {
        Preconditions.checkArgument(
            !args.androidSdkProguardConfig.isPresent(),
            "The deprecated use_android_proguard_config_with_optimizations parameter" +
                " cannot be used with android_sdk_proguard_config.");
        LOG.error(
            "Target %s specified use_android_proguard_config_with_optimizations, " +
                "which is deprecated. Use android_sdk_proguard_config.",
            params.getBuildTarget());
        androidSdkProguardConfig = args.useAndroidProguardConfigWithOptimizations.orElse(false)
            ? ProGuardObfuscateStep.SdkProguardType.OPTIMIZED
            : ProGuardObfuscateStep.SdkProguardType.DEFAULT;
      }

      EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
      if (!args.exopackageModes.isEmpty()) {
        exopackageModes = EnumSet.copyOf(args.exopackageModes);
      } else if (args.exopackage.orElse(false)) {
        LOG.error(
            "Target %s specified exopackage=True, which is deprecated. Use exopackage_modes.",
            params.getBuildTarget());
        exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
      }

      DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

      PackageType packageType = getPackageType(args);
      boolean shouldPreDex = !args.disablePreDex &&
          PackageType.DEBUG.equals(packageType) &&
          !args.preprocessJavaClassesBash.isPresent();

      ResourceFilter resourceFilter =
        new ResourceFilter(args.resourceFilter);

      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
          params,
          resolver,
          args.aaptMode,
          compressionMode,
          resourceFilter,
          args.getBannedDuplicateResourceTypes(),
          args.resourceUnionPackage,
          addFallbackLocales(args.locales),
          args.manifest,
          packageType,
          ImmutableSet.copyOf(args.cpuFilters),
          args.buildStringSourceMap,
          shouldPreDex,
          AndroidBinary.getPrimaryDexPath(params.getBuildTarget(), params.getProjectFilesystem()),
          dexSplitMode,
          ImmutableSet.copyOf(args.noDx.orElse(ImmutableSet.of())),
          /* resourcesToExclude */ ImmutableSet.of(),
          args.skipCrunchPngs,
          args.includesVectorDrawables,
          javaBuckConfig,
          JavacFactory.create(ruleFinder, javaBuckConfig, null),
          javacOptions,
          exopackageModes,
          args.buildConfigValues,
          args.buildConfigValuesFile,
          Optional.empty(),
          args.trimResourceIds,
          args.keepResourcePattern,
          nativePlatforms,
          Optional.of(args.nativeLibraryMergeMap),
          args.nativeLibraryMergeGlue,
          args.nativeLibraryMergeCodeGenerator,
          args.nativeLibraryProguardConfigGenerator,
          args.enableRelinker ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
          dxExecutorService,
          args.manifestEntries,
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

      // Build rules added to "no_dx" are only hints, not hard dependencies. Therefore, although a
      // target may be mentioned in that parameter, it may not be present as a build rule.
      ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
      for (BuildTarget noDxTarget : args.noDx.orElse(ImmutableSet.of())) {
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

      ImmutableSortedSet<BuildRule> redexExtraDeps = redexOptions
          .map(a -> a.getRedexExtraArgs()
              .stream()
              .flatMap(arg -> arg.getDeps(ruleFinder).stream())
              .collect(MoreCollectors.toImmutableSortedSet(Ordering.natural()))
          ).orElse(ImmutableSortedSet.of());

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
          Optional.of(args.proguardJvmArgs),
          proGuardConfig.getProguardAgentPath(),
          (Keystore) keystore,
          packageType,
          dexSplitMode,
          args.noDx.orElse(ImmutableSet.of()),
          androidSdkProguardConfig,
          args.optimizationPasses,
          args.proguardConfig,
          args.skipProguard,
          redexOptions,
          compressionMode,
          args.cpuFilters,
          resourceFilter,
          exopackageModes,
          MACRO_HANDLER.getExpander(
              params.getBuildTarget(),
              cellRoots,
              resolver),
          args.preprocessJavaClassesBash,
          rulesToExcludeFromDex,
          result,
          args.reorderClassesIntraDex,
          args.dexReorderToolFile,
          args.dexReorderDataDumpFile,
          args.xzCompressionLevel,
          dxExecutorService,
          args.packageAssetLibraries,
          args.compressAssetLibraries,
          args.manifestEntries,
          javaOptions.getJavaRuntimeLauncher(),
          dxConfig.getDxMaxHeapSize());
    }
  }

  private DexSplitMode createDexSplitMode(Arg args, EnumSet<ExopackageMode> exopackageModes) {
    // Exopackage builds default to JAR, otherwise, default to RAW.
    DexStore defaultDexStore = ExopackageMode.enabledForSecondaryDexes(exopackageModes)
        ? DexStore.JAR
        : DexStore.RAW;
    DexSplitStrategy dexSplitStrategy = args.minimizePrimaryDexSize
        ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
        : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.useSplitDex,
        dexSplitStrategy,
        args.dexCompression.orElse(defaultDexStore),
        args.linearAllocHardLimit.orElse(DEFAULT_LINEAR_ALLOC_HARD_LIMIT),
        args.primaryDexPatterns,
        args.primaryDexClassesFile,
        args.primaryDexScenarioFile,
        args.primaryDexScenarioOverflowAllowed,
        args.secondaryDexHeadClassesFile,
        args.secondaryDexTailClassesFile);
  }

  private PackageType getPackageType(Arg args) {
    if (!args.packageType.isPresent()) {
      return PackageType.DEBUG;
    }
    return PackageType.valueOf(args.packageType.get().toUpperCase(Locale.US));
  }

  private ResourceCompressionMode getCompressionMode(Arg args) {
    if (!args.resourceCompression.isPresent()) {
      return ResourceCompressionMode.DISABLED;
    }
    return ResourceCompressionMode.valueOf(args.resourceCompression.get().toUpperCase(Locale.US));
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
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.redex.orElse(false)) {
      // If specified, this option may point to either a BuildTarget or a file.
      Optional<BuildTarget> redexTarget = buckConfig.getMaybeBuildTarget(
          SECTION,
          CONFIG_PARAM_REDEX);
      if (redexTarget.isPresent()) {
        extraDepsBuilder.add(redexTarget.get());
      }

      constructorArg.redexExtraArgs.forEach(a ->
        addDepsFromParam(buildTarget, cellRoots, a, extraDepsBuilder, targetGraphOnlyDepsBuilder)
      );
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
          target,
          cellNames,
          paramValue,
          buildDefsBuilder,
          nonBuildDefsBuilder);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  private Optional<com.facebook.buck.rules.args.Arg> getPostFilterResourcesArgs(
      Arg arg,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots) {
    return arg.postFilterResourcesCmd.map(MacroArg.toMacroArgFunction(
        MACRO_HANDLER,
        params.getBuildTarget(),
        cellRoots,
        resolver)::apply);
  }

  private Optional<RedexOptions> getRedexOptions(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      Arg arg) {
    boolean redexRequested = arg.redex.orElse(false);
    if (!redexRequested) {
      return Optional.empty();
    }

    Optional<Tool> redexBinary =
        buckConfig.getTool(SECTION, CONFIG_PARAM_REDEX, resolver);
    if (!redexBinary.isPresent()) {
      throw new HumanReadableException("Requested running ReDex for %s but the path to the tool" +
          "has not been specified in the %s.%s .buckconfig section.",
          params.getBuildTarget(),
          SECTION,
          CONFIG_PARAM_REDEX);
    }

    java.util.function.Function<String, com.facebook.buck.rules.args.Arg> macroArgFunction =
        MacroArg.toMacroArgFunction(
            MACRO_HANDLER,
            params.getBuildTarget(),
            cellRoots,
            resolver)::apply;
    List<com.facebook.buck.rules.args.Arg> redexExtraArgs = arg.redexExtraArgs.stream()
        .map(macroArgFunction)
        .collect(Collectors.toList());

    return Optional.of(RedexOptions.builder()
        .setRedex(redexBinary.get())
        .setRedexConfig(arg.redexConfig)
        .setRedexExtraArgs(redexExtraArgs)
        .build());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public SourcePath manifest;
    public BuildTarget keystore;
    public Optional<String> packageType;
    @Hint(isDep = false) public Optional<Set<BuildTarget>> noDx = Optional.of(ImmutableSet.of());
    public Boolean useSplitDex = false;
    public Boolean minimizePrimaryDexSize = false;
    public Boolean disablePreDex = false;
    // TODO(natthu): mark this as deprecated.
    public Optional<Boolean> exopackage;
    public Set<ExopackageMode> exopackageModes = ImmutableSet.of();
    public Optional<DexStore> dexCompression;
    public Optional<ProGuardObfuscateStep.SdkProguardType> androidSdkProguardConfig;
    public Optional<Boolean> useAndroidProguardConfigWithOptimizations;
    public Optional<Integer> optimizationPasses;
    public List<String> proguardJvmArgs = ImmutableList.of();
    public Optional<SourcePath> proguardConfig;
    public Optional<String> resourceCompression;
    public boolean skipCrunchPngs = false;
    public boolean includesVectorDrawables = false;
    public List<String> primaryDexPatterns = ImmutableList.of();
    public Optional<SourcePath> primaryDexClassesFile;
    public Optional<SourcePath> primaryDexScenarioFile;
    public boolean primaryDexScenarioOverflowAllowed = false;
    public Optional<SourcePath> secondaryDexHeadClassesFile;
    public Optional<SourcePath> secondaryDexTailClassesFile;
    public Set<BuildTarget> applicationModuleTargets = ImmutableSet.of();
    public Optional<Long> linearAllocHardLimit;
    public List<String> resourceFilter = ImmutableList.of();

    // Do not inspect these directly, use getBannedDuplicateResourceTypes.
    // Ideally these should be private, but Arg-population doesn't allow that.
    //
    // If set to ALLOW_BY_DEFAULT, bannedDuplicateResourceTypes is used and setting
    // allowedDuplicateResourceTypes is an error.
    //
    // If set to BAN_BY_DEFAULT, allowedDuplicateResourceTypes is used and setting
    // bannedDuplicateResourceTypes is an error.
    // This only exists to enable migration from allowing by default to banning by default.
    public DuplicateResourceBehaviour duplicateResourceBehavior =
        DuplicateResourceBehaviour.ALLOW_BY_DEFAULT;
    public Set<RType> bannedDuplicateResourceTypes = ImmutableSet.of();
    public Set<RType> allowedDuplicateResourceTypes = ImmutableSet.of();

    public AndroidBinary.AaptMode aaptMode = AndroidBinary.AaptMode.AAPT1;
    public boolean trimResourceIds = false;
    public Optional<String> keepResourcePattern;
    public Optional<String> resourceUnionPackage;
    public ImmutableSet<String> locales = ImmutableSet.of();
    public boolean buildStringSourceMap = false;
    public Set<TargetCpuType> cpuFilters = ImmutableSet.of();
    public ImmutableSortedSet<BuildTarget> preprocessJavaClassesDeps = ImmutableSortedSet.of();
    public Optional<String> preprocessJavaClassesBash;
    public boolean reorderClassesIntraDex = false;
    public Optional<SourcePath> dexReorderToolFile;
    public Optional<SourcePath> dexReorderDataDumpFile;
    public Optional<Integer> xzCompressionLevel;
    public boolean packageAssetLibraries = false;
    public boolean compressAssetLibraries = false;
    public Map<String, List<Pattern>> nativeLibraryMergeMap = ImmutableMap.of();
    public Optional<BuildTarget> nativeLibraryMergeGlue;
    public Optional<BuildTarget> nativeLibraryMergeCodeGenerator;
    public Optional<BuildTarget> nativeLibraryProguardConfigGenerator;
    public boolean enableRelinker = false;
    public ManifestEntries manifestEntries = ManifestEntries.empty();
    public BuildConfigFields buildConfigValues = BuildConfigFields.empty();
    public Optional<Boolean> redex;
    public Optional<SourcePath> redexConfig;
    public ImmutableList<String> redexExtraArgs = ImmutableList.of();
    public Optional<String> postFilterResourcesCmd = Optional.empty();

    public Optional<SourcePath> buildConfigValuesFile;
    public boolean skipProguard = false;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }

    public EnumSet<RType> getBannedDuplicateResourceTypes() {
      if (duplicateResourceBehavior == Arg.DuplicateResourceBehaviour.ALLOW_BY_DEFAULT) {
        if (!allowedDuplicateResourceTypes.isEmpty()) {
          throw new IllegalArgumentException("Cannot set allowed_duplicate_resource_types if " +
              "duplicate_resource_behaviour is allow_by_default");
        }
        if (!bannedDuplicateResourceTypes.isEmpty()) {
          return EnumSet.copyOf(bannedDuplicateResourceTypes);
        } else {
          return EnumSet.noneOf(RType.class);
        }
      } else if (duplicateResourceBehavior == Arg.DuplicateResourceBehaviour.BAN_BY_DEFAULT) {
        if (!bannedDuplicateResourceTypes.isEmpty()) {
          throw new IllegalArgumentException("Cannot set banned_duplicate_resource_types if " +
              "duplicate_resource_behaviour is ban_by_default");
        }
        if (!allowedDuplicateResourceTypes.isEmpty()) {
          return EnumSet.complementOf(EnumSet.copyOf(allowedDuplicateResourceTypes));
        } else {
          return EnumSet.allOf(RType.class);
        }
      } else {
        throw new IllegalArgumentException(
            "Unrecognized duplicate_resource_behavior: " + duplicateResourceBehavior);
      }
    }

    public enum DuplicateResourceBehaviour {
      ALLOW_BY_DEFAULT,
      BAN_BY_DEFAULT
    }
  }
}
