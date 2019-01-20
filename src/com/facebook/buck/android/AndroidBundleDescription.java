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
import com.facebook.buck.android.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class AndroidBundleDescription
    implements DescriptionWithTargetGraph<AndroidBundleDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidBundleDescription.AbstractAndroidBundleDescriptionArg> {

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
  private final ToolchainProvider toolchainProvider;
  private final AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory;
  private final AndroidBundleFactory androidBundleFactory;
  private final JavacFactory javacFactory;
  private final Supplier<JavaOptions> javaOptions;

  public AndroidBundleDescription(
      JavaBuckConfig javaBuckConfig,
      AndroidBuckConfig androidBuckConfig,
      ProGuardConfig proGuardConfig,
      BuckConfig buckConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig,
      ToolchainProvider toolchainProvider,
      AndroidBinaryGraphEnhancerFactory androidBinaryGraphEnhancerFactory,
      AndroidBundleFactory androidBundleFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.androidBuckConfig = androidBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.dxConfig = dxConfig;
    this.androidInstallConfig = new AndroidInstallConfig(buckConfig);
    this.toolchainProvider = toolchainProvider;
    this.androidBinaryGraphEnhancerFactory = androidBinaryGraphEnhancerFactory;
    this.androidBundleFactory = androidBundleFactory;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
    this.javaOptions = JavaOptionsProvider.getDefaultJavaOptions(toolchainProvider);
  }

  @Override
  public Class<AndroidBundleDescriptionArg> getConstructorArgType() {
    return AndroidBundleDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidBundleDescriptionArg args) {
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

    // Check if the aapt mode is supportive for Bundle Build
    if (args.getAaptMode() != AaptMode.AAPT2) {
      throw new HumanReadableException(
          "Android App Bundle can only be built with aapt2, but %s is using aapt1.", buildTarget);
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
            args,
            true,
            javaOptions.get(),
            javacFactory);
    AndroidBundle androidBundle =
        androidBundleFactory.create(
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
            args,
            javaOptions.get());
    // The exo installer is always added to the index so that the action graph is the same
    // between build and install calls.
    new AndroidBinaryInstallGraphEnhancer(
            androidInstallConfig, projectFilesystem, buildTarget, androidBundle)
        .enhance(graphBuilder);
    return androidBundle;
  }

  private static DexSplitMode createDexSplitMode(
      AndroidBundleDescriptionArg args, EnumSet<ExopackageMode> exopackageModes) {
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
        args.getSecondaryDexTailClassesFile(),
        args.isAllowRDotJavaInSecondaryDex());
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
      AbstractAndroidBundleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(targetGraphOnlyDepsBuilder, null);
    javaOptions.get().addParseTimeDeps(targetGraphOnlyDepsBuilder);
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
  abstract static class AbstractAndroidBundleDescriptionArg
      implements CommonDescriptionArg,
          HasDeclaredDeps,
          HasExopackageArgs,
          HasTests,
          AndroidGraphEnhancerArgs {
    abstract BuildTarget getKeystore();

    @Value.Default
    boolean getUseSplitDex() {
      return false;
    }

    @Value.Default
    boolean getMinimizePrimaryDexSize() {
      return false;
    }

    abstract Optional<DexStore> getDexCompression();

    abstract List<String> getPrimaryDexPatterns();

    abstract Optional<SourcePath> getPrimaryDexClassesFile();

    abstract Optional<SourcePath> getPrimaryDexScenarioFile();

    @Value.Default
    boolean isPrimaryDexScenarioOverflowAllowed() {
      return false;
    }

    abstract Optional<SourcePath> getSecondaryDexHeadClassesFile();

    abstract Optional<SourcePath> getSecondaryDexTailClassesFile();

    abstract Optional<SourcePath> getAndroidAppModularityResult();

    @Value.Default
    long getLinearAllocHardLimit() {
      return DexSplitMode.DEFAULT_LINEAR_ALLOC_HARD_LIMIT;
    }

    abstract List<String> getResourceFilter();

    @Value.NaturalOrder
    abstract ImmutableSortedSet<BuildTarget> getPreprocessJavaClassesDeps();

    abstract OptionalInt getXzCompressionLevel();

    @Value.Default
    boolean isPackageAssetLibraries() {
      return false;
    }

    @Value.Default
    boolean isCompressAssetLibraries() {
      return false;
    }

    abstract Optional<CompressionAlgorithm> getAssetCompressionAlgorithm();

    @Value.Default
    boolean getRedex() {
      return false;
    }

    abstract Optional<SourcePath> getRedexConfig();

    abstract ImmutableList<StringWithMacros> getRedexExtraArgs();

    @Override
    @Value.Default
    public AaptMode getAaptMode() {
      return AaptMode.AAPT2;
    }
  }
}
