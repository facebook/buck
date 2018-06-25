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

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class AndroidBinaryDescription
    implements DescriptionWithTargetGraph<AndroidBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidBinaryDescription.AbstractAndroidBinaryDescriptionArg> {

  private static final ImmutableSet<Flavor> FLAVORS =
      ImmutableSet.of(
          PACKAGE_STRING_ASSETS_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.AAPT2_LINK_FLAVOR,
          AndroidBinaryGraphEnhancer.UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR,
          AndroidBinaryGraphEnhancer.PROGUARD_TEXT_OUTPUT_FLAVOR,
          AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_RESOURCES_FLAVOR);

  private final JavaBuckConfig javaBuckConfig;
  private final AndroidBuckConfig androidBuckConfig;
  private final ProGuardConfig proGuardConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DxConfig dxConfig;
  private final AndroidInstallConfig androidInstallConfig;
  private final ApkConfig apkConfig;
  private final ToolchainProvider toolchainProvider;
  private final AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory;
  private final AndroidBinaryFactory androidBinaryFactory;

  public AndroidBinaryDescription(
      JavaBuckConfig javaBuckConfig,
      AndroidBuckConfig androidBuckConfig,
      ProGuardConfig proGuardConfig,
      BuckConfig buckConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig,
      ApkConfig apkConfig,
      ToolchainProvider toolchainProvider,
      AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory,
      AndroidBinaryFactory androidBinaryFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.androidBuckConfig = androidBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.dxConfig = dxConfig;
    this.androidInstallConfig = new AndroidInstallConfig(buckConfig);
    this.apkConfig = apkConfig;
    this.toolchainProvider = toolchainProvider;
    this.androidBinaryGraphEnhancerFactory = androidBinaryGraphEnhancerFactory;
    this.androidBinaryFactory = androidBinaryFactory;
  }

  @Override
  public Class<AndroidBinaryDescriptionArg> getConstructorArgType() {
    return AndroidBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBinaryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    params = params.withoutExtraDeps();

    // All of our supported flavors are constructed as side-effects
    // of the main target.
    for (Flavor flavor : FLAVORS) {
      if (buildTarget.getFlavors().contains(flavor)) {
        graphBuilder.requireRule(buildTarget.withoutFlavors(flavor));
        return graphBuilder.getRule(buildTarget);
      }
    }

    // We don't support requiring other flavors right now.
    if (buildTarget.isFlavored()) {
      throw new HumanReadableException(
          "Requested target %s contains an unrecognized flavor", buildTarget);
    }

    EnumSet<ExopackageMode> exopackageModes =
        ExopackageArgsHelper.detectExopackageModes(buildTarget, args);

    DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        NoDxArgsHelper.findRulesToExcludeFromDex(graphBuilder, buildTarget, args.getNoDx());

    CellPathResolver cellRoots = context.getCellPathResolver();

    ResourceFilter resourceFilter = new ResourceFilter(args.getResourceFilter());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    AndroidBinaryGraphEnhancer graphEnhancer =
        androidBinaryGraphEnhancerFactory.create(
            toolchainProvider,
            javaBuckConfig,
            cxxBuckConfig,
            dxConfig,
            proGuardConfig,
            cellRoots,
            context.getTargetGraph(),
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            resourceFilter,
            dexSplitMode,
            exopackageModes,
            rulesToExcludeFromDex,
            args);
    AndroidBinary androidBinary =
        androidBinaryFactory.create(
            toolchainProvider,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            buildTarget,
            params,
            graphEnhancer,
            dexSplitMode,
            exopackageModes,
            resourceFilter,
            rulesToExcludeFromDex,
            apkConfig,
            args);
    // The exo installer is always added to the index so that the action graph is the same
    // between build and install calls.
    new AndroidBinaryInstallGraphEnhancer(
            androidInstallConfig, projectFilesystem, buildTarget, androidBinary)
        .enhance(graphBuilder);
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
      Optional<BuildTarget> redexTarget = androidBuckConfig.getRedexTarget();
      if (redexTarget.isPresent()) {
        extraDepsBuilder.add(redexTarget.get());
      }
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractAndroidBinaryDescriptionArg
      implements CommonDescriptionArg,
          HasDeclaredDeps,
          HasExopackageArgs,
          HasTests,
          HasDuplicateAndroidResourceTypes {
    abstract Optional<SourcePath> getManifest();

    abstract Optional<SourcePath> getManifestSkeleton();

    abstract Optional<SourcePath> getModuleManifestSkeleton();

    abstract BuildTarget getKeystore();

    abstract Optional<String> getPackageType();

    @Hint(isDep = false)
    abstract ImmutableSet<BuildTarget> getNoDx();

    @Value.Default
    boolean getUseSplitDex() {
      return false;
    }

    @Value.Default
    boolean getMinimizePrimaryDexSize() {
      return false;
    }

    @Value.Default
    boolean getDisablePreDex() {
      return false;
    }

    abstract Optional<DexStore> getDexCompression();

    abstract Optional<ProGuardObfuscateStep.SdkProguardType> getAndroidSdkProguardConfig();

    abstract OptionalInt getOptimizationPasses();

    abstract List<String> getProguardJvmArgs();

    abstract Optional<SourcePath> getProguardConfig();

    @Value.Default
    ResourceCompressionMode getResourceCompression() {
      return ResourceCompressionMode.DISABLED;
    }

    @Value.Default
    boolean isSkipCrunchPngs() {
      return false;
    }

    @Value.Default
    boolean isIncludesVectorDrawables() {
      return false;
    }

    @Value.Default
    boolean isNoAutoVersionResources() {
      return false;
    }

    @Value.Default
    boolean isNoVersionTransitionsResources() {
      return false;
    }

    @Value.Default
    boolean isNoAutoAddOverlayResources() {
      return false;
    }

    abstract List<String> getPrimaryDexPatterns();

    abstract Optional<SourcePath> getPrimaryDexClassesFile();

    abstract Optional<SourcePath> getPrimaryDexScenarioFile();

    @Value.Default
    boolean isPrimaryDexScenarioOverflowAllowed() {
      return false;
    }

    abstract Optional<SourcePath> getSecondaryDexHeadClassesFile();

    abstract Optional<SourcePath> getSecondaryDexTailClassesFile();

    abstract Set<BuildTarget> getApplicationModuleTargets();

    abstract Optional<SourcePath> getAndroidAppModularityResult();

    abstract Map<String, List<BuildTarget>> getApplicationModuleConfigs();

    abstract Optional<Map<String, List<String>>> getApplicationModuleDependencies();

    @Value.Default
    long getLinearAllocHardLimit() {
      return DexSplitMode.DEFAULT_LINEAR_ALLOC_HARD_LIMIT;
    }

    abstract List<String> getResourceFilter();

    @Value.Default
    boolean getIsCacheable() {
      return true;
    }

    @Value.Default
    AndroidBinary.AaptMode getAaptMode() {
      return AndroidBinary.AaptMode.AAPT1;
    }

    @Value.Default
    boolean isTrimResourceIds() {
      return false;
    }

    abstract Optional<String> getKeepResourcePattern();

    abstract Optional<String> getResourceUnionPackage();

    abstract ImmutableSet<String> getLocales();

    abstract Optional<String> getLocalizedStringFileName();

    @Value.Default
    boolean isBuildStringSourceMap() {
      return false;
    }

    @Value.Default
    boolean isIgnoreAaptProguardConfig() {
      return false;
    }

    abstract Set<TargetCpuType> getCpuFilters();

    @Value.NaturalOrder
    abstract ImmutableSortedSet<BuildTarget> getPreprocessJavaClassesDeps();

    abstract Optional<StringWithMacros> getPreprocessJavaClassesBash();

    @Value.Default
    boolean isReorderClassesIntraDex() {
      return false;
    }

    @Value.Default
    String getDexTool() {
      return DxStep.DX;
    }

    abstract Optional<SourcePath> getDexReorderToolFile();

    abstract Optional<SourcePath> getDexReorderDataDumpFile();

    abstract OptionalInt getXzCompressionLevel();

    @Value.Default
    boolean isPackageAssetLibraries() {
      return false;
    }

    @Value.Default
    boolean isCompressAssetLibraries() {
      return false;
    }

    abstract Map<String, List<Pattern>> getNativeLibraryMergeMap();

    abstract Optional<BuildTarget> getNativeLibraryMergeGlue();

    abstract Optional<BuildTarget> getNativeLibraryMergeCodeGenerator();

    abstract Optional<ImmutableSortedSet<String>> getNativeLibraryMergeLocalizedSymbols();

    abstract Optional<BuildTarget> getNativeLibraryProguardConfigGenerator();

    @Value.Default
    boolean isEnableRelinker() {
      return false;
    }

    abstract ImmutableList<Pattern> getRelinkerWhitelist();

    @Value.Default
    ManifestEntries getManifestEntries() {
      return ManifestEntries.empty();
    }

    @Value.Default
    BuildConfigFields getBuildConfigValues() {
      return BuildConfigFields.of();
    }

    @Value.Default
    boolean getRedex() {
      return false;
    }

    abstract Optional<SourcePath> getRedexConfig();

    abstract ImmutableList<StringWithMacros> getRedexExtraArgs();

    abstract Optional<StringWithMacros> getPostFilterResourcesCmd();

    abstract Optional<SourcePath> getBuildConfigValuesFile();

    @Value.Default
    boolean isSkipProguard() {
      return false;
    }
  }
}
