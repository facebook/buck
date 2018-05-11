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

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
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

  private static final ImmutableList<AbstractMacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(new ExecutableMacroExpander(), new LocationMacroExpander());

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  private static final Flavor ANDROID_MODULARITY_VERIFICATION_FLAVOR =
      InternalFlavor.of("modularity_verification");

  private static final ImmutableSet<Flavor> FLAVORS =
      ImmutableSet.of(
          PACKAGE_STRING_ASSETS_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.AAPT2_LINK_FLAVOR,
          AndroidBinaryGraphEnhancer.UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR,
          AndroidBinaryGraphEnhancer.PROGUARD_TEXT_OUTPUT_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_RESOURCES_FLAVOR);

  private final JavaBuckConfig javaBuckConfig;
  private final ProGuardConfig proGuardConfig;
  private final BuckConfig buckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DxConfig dxConfig;
  private final AndroidInstallConfig androidInstallConfig;
  private final ApkConfig apkConfig;

  public AndroidBinaryDescription(
      JavaBuckConfig javaBuckConfig,
      ProGuardConfig proGuardConfig,
      BuckConfig buckConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig,
      ApkConfig apkConfig) {
    this.javaBuckConfig = javaBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.buckConfig = buckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.dxConfig = dxConfig;
    this.androidInstallConfig = new AndroidInstallConfig(buckConfig);
    this.apkConfig = apkConfig;
  }

  @Override
  public Class<AndroidBinaryDescriptionArg> getConstructorArgType() {
    return AndroidBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBinaryDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();

    params = params.withoutExtraDeps();

    // All of our supported flavors are constructed as side-effects
    // of the main target.
    for (Flavor flavor : FLAVORS) {
      if (buildTarget.getFlavors().contains(flavor)) {
        resolver.requireRule(buildTarget.withoutFlavors(flavor));
        return resolver.getRule(buildTarget);
      }
    }

    // We don't support requiring other flavors right now.
    if (buildTarget.isFlavored()) {
      throw new HumanReadableException(
          "Requested target %s contains an unrecognized flavor", buildTarget);
    }

    BuildRule keystore = resolver.getRule(args.getKeystore());
    if (!(keystore instanceof Keystore)) {
      throw new HumanReadableException(
          "In %s, keystore='%s' must be a keystore() but was %s().",
          buildTarget, keystore.getFullyQualifiedName(), keystore.getType());
    }

    APKModuleGraph apkModuleGraph = null;
    if (!args.getApplicationModuleConfigs().isEmpty()) {
      apkModuleGraph =
          new APKModuleGraph(
              Optional.of(args.getApplicationModuleConfigs()),
              args.getApplicationModuleDependencies(),
              context.getTargetGraph(),
              buildTarget);
    } else {
      apkModuleGraph =
          new APKModuleGraph(
              context.getTargetGraph(),
              buildTarget,
              Optional.of(args.getApplicationModuleTargets()));
    }

    EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
    if (!args.getExopackageModes().isEmpty()) {
      exopackageModes = EnumSet.copyOf(args.getExopackageModes());
    } else if (args.isExopackage().orElse(false)) {
      LOG.error(
          "Target %s specified exopackage=True, which is deprecated. Use exopackage_modes.",
          buildTarget);
      exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
    }

    DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

    PackageType packageType = getPackageType(args);

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.getAndroidSdkProguardConfig().orElse(ProGuardObfuscateStep.SdkProguardType.NONE);

    boolean shouldProguard =
        args.getProguardConfig().isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(androidSdkProguardConfig);

    boolean shouldPreDex =
        !args.getDisablePreDex()
            && !shouldProguard
            && !args.getPreprocessJavaClassesBash().isPresent();

    // Build rules added to "no_dx" are only hints, not hard dependencies. Therefore, although a
    // target may be mentioned in that parameter, it may not be present as a build rule.
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (BuildTarget noDxTarget : args.getNoDx()) {
      Optional<BuildRule> ruleOptional = resolver.getRuleOptional(noDxTarget);
      if (ruleOptional.isPresent()) {
        builder.add(ruleOptional.get());
      } else {
        LOG.info("%s: no_dx target not a dependency: %s", buildTarget, noDxTarget);
      }
    }

    ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = builder.build();
    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        RichStream.from(buildRulesToExcludeFromDex)
            .filter(JavaLibrary.class)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    ListeningExecutorService dxExecutorService =
        toolchainProvider
            .getByName(DxToolchain.DEFAULT_NAME, DxToolchain.class)
            .getDxExecutorService();

    JavaOptionsProvider javaOptionsProvider =
        toolchainProvider.getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class);

    CellPathResolver cellRoots = context.getCellPathResolver();

    NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs =
        NonPredexedDexBuildableArgs.builder()
            .setProguardAgentPath(proGuardConfig.getProguardAgentPath())
            .setProguardJarOverride(proGuardConfig.getProguardJarOverride())
            .setProguardMaxHeapSize(proGuardConfig.getProguardMaxHeapSize())
            .setSdkProguardConfig(androidSdkProguardConfig)
            .setPreprocessJavaClassesBash(
                getPreprocessJavaClassesBash(args, buildTarget, resolver, cellRoots))
            .setReorderClassesIntraDex(args.isReorderClassesIntraDex())
            .setDexReorderToolFile(args.getDexReorderToolFile())
            .setDexReorderDataDumpFile(args.getDexReorderDataDumpFile())
            .setDxExecutorService(dxExecutorService)
            .setDxMaxHeapSize(dxConfig.getDxMaxHeapSize())
            .setOptimizationPasses(args.getOptimizationPasses())
            .setProguardJvmArgs(args.getProguardJvmArgs())
            .setSkipProguard(args.isSkipProguard())
            .setJavaRuntimeLauncher(javaOptionsProvider.getJavaOptions().getJavaRuntimeLauncher())
            .setProguardConfigPath(args.getProguardConfig())
            .setShouldProguard(shouldProguard)
            .build();

    ResourceFilter resourceFilter = new ResourceFilter(args.getResourceFilter());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            toolchainProvider,
            cellRoots,
            buildTarget,
            projectFilesystem,
            androidPlatformTarget,
            params,
            resolver,
            args.getAaptMode(),
            args.getResourceCompression(),
            resourceFilter,
            args.getEffectiveBannedDuplicateResourceTypes(),
            args.getDuplicateResourceWhitelist(),
            args.getResourceUnionPackage(),
            addFallbackLocales(args.getLocales()),
            args.getLocalizedStringFileName(),
            args.getManifest(),
            args.getManifestSkeleton(),
            args.getModuleManifestSkeleton(),
            packageType,
            ImmutableSet.copyOf(args.getCpuFilters()),
            args.isBuildStringSourceMap(),
            shouldPreDex,
            dexSplitMode,
            args.getNoDx(),
            /* resourcesToExclude */ ImmutableSet.of(),
            args.isSkipCrunchPngs(),
            args.isIncludesVectorDrawables(),
            args.isNoAutoVersionResources(),
            args.isNoVersionTransitionsResources(),
            args.isNoAutoAddOverlayResources(),
            javaBuckConfig,
            JavacFactory.create(ruleFinder, javaBuckConfig, null),
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            exopackageModes,
            args.getBuildConfigValues(),
            args.getBuildConfigValuesFile(),
            Optional.empty(),
            args.isTrimResourceIds(),
            args.getKeepResourcePattern(),
            args.isIgnoreAaptProguardConfig(),
            Optional.of(args.getNativeLibraryMergeMap()),
            args.getNativeLibraryMergeGlue(),
            args.getNativeLibraryMergeCodeGenerator(),
            args.getNativeLibraryMergeLocalizedSymbols(),
            shouldProguard ? args.getNativeLibraryProguardConfigGenerator() : Optional.empty(),
            args.isEnableRelinker() ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
            args.getRelinkerWhitelist(),
            dxExecutorService,
            args.getManifestEntries(),
            cxxBuckConfig,
            apkModuleGraph,
            dxConfig,
            args.getDexTool(),
            getPostFilterResourcesArgs(args, buildTarget, resolver, cellRoots),
            nonPreDexedDexBuildableArgs,
            rulesToExcludeFromDex);
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    Optional<BuildRule> moduleVerification;
    if (args.getAndroidAppModularityResult().isPresent()) {
      moduleVerification =
          Optional.of(
              new AndroidAppModularityVerification(
                  ruleFinder,
                  buildTarget.withFlavors(ANDROID_MODULARITY_VERIFICATION_FLAVOR),
                  projectFilesystem,
                  args.getAndroidAppModularityResult().get(),
                  args.isSkipProguard(),
                  result.getDexFilesInfo().proguardTextFilesPath,
                  result.getPackageableCollection()));
      resolver.addToIndex(moduleVerification.get());
    } else {
      moduleVerification = Optional.empty();
    }

    AndroidBinaryFilesInfo filesInfo =
        new AndroidBinaryFilesInfo(result, exopackageModes, args.isPackageAssetLibraries());

    AndroidBinary androidBinary =
        new AndroidBinary(
            buildTarget,
            projectFilesystem,
            toolchainProvider.getByName(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class),
            androidPlatformTarget,
            params,
            ruleFinder,
            Optional.of(args.getProguardJvmArgs()),
            (Keystore) keystore,
            dexSplitMode,
            args.getNoDx(),
            androidSdkProguardConfig,
            args.getOptimizationPasses(),
            args.getProguardConfig(),
            args.isSkipProguard(),
            getRedexOptions(buildTarget, resolver, cellRoots, args),
            args.getResourceCompression(),
            args.getCpuFilters(),
            resourceFilter,
            exopackageModes,
            rulesToExcludeFromDex,
            result,
            args.getXzCompressionLevel(),
            args.isPackageAssetLibraries(),
            args.isCompressAssetLibraries(),
            args.getManifestEntries(),
            javaOptionsProvider.getJavaOptions().getJavaRuntimeLauncher(),
            args.getIsCacheable(),
            moduleVerification,
            filesInfo.getDexFilesInfo(),
            filesInfo.getNativeFilesInfo(),
            filesInfo.getResourceFilesInfo(),
            ImmutableSortedSet.copyOf(result.getAPKModuleGraph().getAPKModules()),
            filesInfo.getExopackageInfo(),
            apkConfig.getCompressionLevel());
    // The exo installer is always added to the index so that the action graph is the same
    // between build and install calls.
    new AndroidBinaryInstallGraphEnhancer(
            androidInstallConfig, projectFilesystem, buildTarget, androidBinary)
        .enhance(resolver);
    return androidBinary;
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
    }
  }

  private Optional<Arg> getPostFilterResourcesArgs(
      AndroidBinaryDescriptionArg arg,
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setResolver(resolver)
            .setExpanders(MACRO_EXPANDERS)
            .build();
    return arg.getPostFilterResourcesCmd().map(macrosConverter::convert);
  }

  private Optional<Arg> getPreprocessJavaClassesBash(
      AndroidBinaryDescriptionArg arg,
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setResolver(resolver)
            .setExpanders(MACRO_EXPANDERS)
            .build();
    return arg.getPreprocessJavaClassesBash().map(macrosConverter::convert);
  }

  private Optional<RedexOptions> getRedexOptions(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidBinaryDescriptionArg arg) {
    boolean redexRequested = arg.getRedex();
    if (!redexRequested) {
      return Optional.empty();
    }

    Optional<Tool> redexBinary =
        buckConfig.getView(ToolConfig.class).getTool(SECTION, CONFIG_PARAM_REDEX, resolver);
    if (!redexBinary.isPresent()) {
      throw new HumanReadableException(
          "Requested running ReDex for %s but the path to the tool"
              + "has not been specified in the %s.%s .buckconfig section.",
          buildTarget, SECTION, CONFIG_PARAM_REDEX);
    }

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setResolver(resolver)
            .setExpanders(MACRO_EXPANDERS)
            .build();
    List<Arg> redexExtraArgs =
        arg.getRedexExtraArgs().stream().map(macrosConverter::convert).collect(Collectors.toList());

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
    Optional<SourcePath> getManifest();

    Optional<SourcePath> getManifestSkeleton();

    Optional<SourcePath> getModuleManifestSkeleton();

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

    Optional<Integer> getOptimizationPasses();

    List<String> getProguardJvmArgs();

    Optional<SourcePath> getProguardConfig();

    @Value.Default
    default ResourceCompressionMode getResourceCompression() {
      return ResourceCompressionMode.DISABLED;
    }

    @Value.Default
    default boolean isSkipCrunchPngs() {
      return false;
    }

    @Value.Default
    default boolean isIncludesVectorDrawables() {
      return false;
    }

    @Value.Default
    default boolean isNoAutoVersionResources() {
      return false;
    }

    @Value.Default
    default boolean isNoVersionTransitionsResources() {
      return false;
    }

    @Value.Default
    default boolean isNoAutoAddOverlayResources() {
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

    Optional<SourcePath> getAndroidAppModularityResult();

    Map<String, List<BuildTarget>> getApplicationModuleConfigs();

    Optional<Map<String, List<String>>> getApplicationModuleDependencies();

    @Value.Default
    default long getLinearAllocHardLimit() {
      return DEFAULT_LINEAR_ALLOC_HARD_LIMIT;
    }

    List<String> getResourceFilter();

    @Value.Default
    default boolean getIsCacheable() {
      return true;
    }

    // Do not inspect this, getAllowedDuplicateResourcesTypes, or getBannedDuplicateResourceTypes
    // directly, use getEffectiveBannedDuplicateResourceTypes.
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

    Optional<SourcePath> getDuplicateResourceWhitelist();

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

    Optional<String> getLocalizedStringFileName();

    @Value.Default
    default boolean isBuildStringSourceMap() {
      return false;
    }

    @Value.Default
    default boolean isIgnoreAaptProguardConfig() {
      return false;
    }

    Set<TargetCpuType> getCpuFilters();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getPreprocessJavaClassesDeps();

    Optional<StringWithMacros> getPreprocessJavaClassesBash();

    @Value.Default
    default boolean isReorderClassesIntraDex() {
      return false;
    }

    @Value.Default
    default String getDexTool() {
      return DxStep.DX;
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

    ImmutableList<Pattern> getRelinkerWhitelist();

    @Value.Default
    default ManifestEntries getManifestEntries() {
      return ManifestEntries.empty();
    }

    @Value.Default
    default BuildConfigFields getBuildConfigValues() {
      return BuildConfigFields.of();
    }

    @Value.Default
    default boolean getRedex() {
      return false;
    }

    Optional<SourcePath> getRedexConfig();

    ImmutableList<StringWithMacros> getRedexExtraArgs();

    Optional<StringWithMacros> getPostFilterResourcesCmd();

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
