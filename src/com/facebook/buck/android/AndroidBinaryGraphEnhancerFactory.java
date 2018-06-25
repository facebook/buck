/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidBinaryGraphEnhancerFactory {

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  public AndroidBinaryGraphEnhancer create(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig,
      ProGuardConfig proGuardConfig,
      CellPathResolver cellPathResolver,
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ResourceFilter resourceFilter,
      DexSplitMode dexSplitMode,
      EnumSet<ExopackageMode> exopackageModes,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidBinaryDescriptionArg args) {

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);
    JavaOptionsProvider javaOptionsProvider =
        toolchainProvider.getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class);

    ListeningExecutorService dxExecutorService =
        toolchainProvider
            .getByName(DxToolchain.DEFAULT_NAME, DxToolchain.class)
            .getDxExecutorService();

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.getAndroidSdkProguardConfig().orElse(ProGuardObfuscateStep.SdkProguardType.NONE);

    boolean shouldProguard =
        args.getProguardConfig().isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(androidSdkProguardConfig);

    boolean shouldPreDex =
        !args.getDisablePreDex()
            && !shouldProguard
            && !args.getPreprocessJavaClassesBash().isPresent();

    APKModuleGraph apkModuleGraph;
    if (args.getApplicationModuleConfigs().isEmpty()) {
      apkModuleGraph =
          new APKModuleGraph(
              targetGraph, buildTarget, Optional.of(args.getApplicationModuleTargets()));
    } else {
      apkModuleGraph =
          new APKModuleGraph(
              Optional.of(args.getApplicationModuleConfigs()),
              args.getApplicationModuleDependencies(),
              targetGraph,
              buildTarget);
    }

    NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs =
        NonPredexedDexBuildableArgs.builder()
            .setProguardAgentPath(proGuardConfig.getProguardAgentPath())
            .setProguardJarOverride(proGuardConfig.getProguardJarOverride())
            .setProguardMaxHeapSize(proGuardConfig.getProguardMaxHeapSize())
            .setSdkProguardConfig(androidSdkProguardConfig)
            .setPreprocessJavaClassesBash(
                getPreprocessJavaClassesBash(args, buildTarget, graphBuilder, cellPathResolver))
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

    return new AndroidBinaryGraphEnhancer(
        toolchainProvider,
        cellPathResolver,
        buildTarget,
        projectFilesystem,
        androidPlatformTarget,
        params,
        graphBuilder,
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
        getPackageType(args),
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
        JavacFactory.getDefault(toolchainProvider),
        toolchainProvider
            .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
            .getJavacOptions(),
        exopackageModes,
        args.getBuildConfigValues(),
        args.getBuildConfigValuesFile(),
        OptionalInt.empty(),
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
        getPostFilterResourcesArgs(args, buildTarget, graphBuilder, cellPathResolver),
        nonPreDexedDexBuildableArgs,
        rulesToExcludeFromDex);
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

  private PackageType getPackageType(AndroidBinaryDescriptionArg args) {
    if (!args.getPackageType().isPresent()) {
      return PackageType.DEBUG;
    }
    return PackageType.valueOf(args.getPackageType().get().toUpperCase(Locale.US));
  }

  private Optional<Arg> getPostFilterResourcesArgs(
      AndroidBinaryDescriptionArg arg,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setExpanders(MacroExpandersForAndroidRules.MACRO_EXPANDERS)
            .build();
    return arg.getPostFilterResourcesCmd().map(x -> macrosConverter.convert(x, graphBuilder));
  }

  private Optional<Arg> getPreprocessJavaClassesBash(
      AndroidBinaryDescriptionArg arg,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setExpanders(MacroExpandersForAndroidRules.MACRO_EXPANDERS)
            .build();
    return arg.getPreprocessJavaClassesBash().map(x -> macrosConverter.convert(x, graphBuilder));
  }
}
