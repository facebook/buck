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

import static com.facebook.buck.jvm.java.JavaLibraryClasspathProvider.getClasspathDeps;

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidInstrumentationApkDescription
    implements Description<AndroidInstrumentationApkDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final ProGuardConfig proGuardConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DxConfig dxConfig;
  private final ApkConfig apkConfig;

  public AndroidInstrumentationApkDescription(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      ProGuardConfig proGuardConfig,
      CxxBuckConfig cxxBuckConfig,
      DxConfig dxConfig,
      ApkConfig apkConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.dxConfig = dxConfig;
    this.apkConfig = apkConfig;
  }

  @Override
  public Class<AndroidInstrumentationApkDescriptionArg> getConstructorArgType() {
    return AndroidInstrumentationApkDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidInstrumentationApkDescriptionArg args) {
    params = params.withoutExtraDeps();
    BuildRule installableApk = resolver.getRule(args.getApk());
    if (!(installableApk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
          buildTarget, installableApk.getFullyQualifiedName(), installableApk.getType());
    }
    AndroidBinary apkUnderTest =
        ApkGenruleDescription.getUnderlyingApk((HasInstallableApk) installableApk);

    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        new ImmutableSortedSet.Builder<>(Ordering.<JavaLibrary>natural())
            .addAll(apkUnderTest.getRulesToExcludeFromDex())
            .addAll(getClasspathDeps(apkUnderTest.getClasspathDeps()))
            .build();

    // TODO(natthu): Instrumentation APKs should also exclude native libraries and assets from the
    // apk under test.
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        apkUnderTest.getAndroidPackageableCollection().getResourceDetails();
    ImmutableSet<BuildTarget> resourcesToExclude =
        ImmutableSet.copyOf(
            Iterables.concat(
                resourceDetails.getResourcesWithNonEmptyResDir(),
                resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()));

    ListeningExecutorService dxExecutorService =
        toolchainProvider
            .getByName(DxToolchain.DEFAULT_NAME, DxToolchain.class)
            .getDxExecutorService();

    boolean shouldProguard =
        apkUnderTest.getProguardConfig().isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(
                apkUnderTest.getSdkProguardConfig());
    NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs =
        NonPredexedDexBuildableArgs.builder()
            .setProguardAgentPath(proGuardConfig.getProguardAgentPath())
            .setProguardJarOverride(proGuardConfig.getProguardJarOverride())
            .setProguardMaxHeapSize(proGuardConfig.getProguardMaxHeapSize())
            .setSdkProguardConfig(apkUnderTest.getSdkProguardConfig())
            .setPreprocessJavaClassesBash(Optional.empty())
            .setReorderClassesIntraDex(false)
            .setDexReorderToolFile(Optional.empty())
            .setDexReorderDataDumpFile(Optional.empty())
            .setDxExecutorService(dxExecutorService)
            .setDxMaxHeapSize(Optional.empty())
            .setOptimizationPasses(apkUnderTest.getOptimizationPasses())
            .setProguardJvmArgs(apkUnderTest.getProguardJvmArgs())
            .setSkipProguard(apkUnderTest.getSkipProguard())
            .setJavaRuntimeLauncher(apkUnderTest.getJavaRuntimeLauncher())
            .setProguardConfigPath(apkUnderTest.getProguardConfig())
            .setShouldProguard(shouldProguard)
            .build();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);

    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            toolchainProvider,
            cellRoots,
            buildTarget,
            projectFilesystem,
            androidPlatformTarget,
            params,
            resolver,
            AndroidBinary.AaptMode.AAPT1,
            ResourceCompressionMode.DISABLED,
            FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
            /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
            Optional.empty(),
            /* resourceUnionPackage */ Optional.empty(),
            /* locales */ ImmutableSet.of(),
            args.getManifest(),
            args.getManifestSkeleton(),
            PackageType.INSTRUMENTED,
            apkUnderTest.getCpuFilters(),
            /* shouldBuildStringSourceMap */ false,
            /* shouldPreDex */ false,
            DexSplitMode.NO_SPLIT,
            rulesToExcludeFromDex
                .stream()
                .map(BuildRule::getBuildTarget)
                .collect(ImmutableSet.toImmutableSet()),
            resourcesToExclude,
            /* skipCrunchPngs */ false,
            args.getIncludesVectorDrawables(),
            /* noAutoVersionResources */ false,
            javaBuckConfig,
            JavacFactory.create(ruleFinder, javaBuckConfig, null),
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            EnumSet.noneOf(ExopackageMode.class),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFile */ Optional.empty(),
            /* xzCompressionLevel */ Optional.empty(),
            /* trimResourceIds */ false,
            /* keepResourcePattern */ Optional.empty(),
            false,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            /* nativeLibraryMergeCodeGenerator */ Optional.empty(),
            /* nativeLibraryProguardConfigGenerator */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
            dxExecutorService,
            apkUnderTest.getManifestEntries(),
            cxxBuckConfig,
            new APKModuleGraph(targetGraph, buildTarget, Optional.empty()),
            dxConfig,
            args.getDexTool(),
            /* postFilterResourcesCommands */ Optional.empty(),
            nonPreDexedDexBuildableArgs,
            rulesToExcludeFromDex);

    AndroidGraphEnhancementResult enhancementResult = graphEnhancer.createAdditionalBuildables();
    AndroidBinaryFilesInfo filesInfo =
        new AndroidBinaryFilesInfo(enhancementResult, EnumSet.noneOf(ExopackageMode.class), false);
    return new AndroidInstrumentationApk(
        buildTarget,
        projectFilesystem,
        toolchainProvider.getByName(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class),
        androidPlatformTarget,
        params,
        ruleFinder,
        apkUnderTest,
        rulesToExcludeFromDex,
        enhancementResult,
        filesInfo.getDexFilesInfo(),
        filesInfo.getNativeFilesInfo(),
        filesInfo.getResourceFilesInfo(),
        filesInfo.getExopackageInfo(),
        apkConfig.getCompressionLevel());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidInstrumentationApkDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    Optional<SourcePath> getManifest();

    Optional<SourcePath> getManifestSkeleton();

    BuildTarget getApk();

    @Value.Default
    default boolean getIncludesVectorDrawables() {
      return false;
    }

    @Value.Default
    default String getDexTool() {
      return DxStep.DX;
    }
  }
}
