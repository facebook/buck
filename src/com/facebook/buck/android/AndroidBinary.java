/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.android.BinaryType.APK;

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.attr.HasInstallHelpers;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 *
 *
 * <pre>
 * android_binary(
 *   name = 'messenger',
 *   manifest = 'AndroidManifest.xml',
 *   deps = [
 *     '//src/com/facebook/messenger:messenger_library',
 *   ],
 * )
 * </pre>
 */
public class AndroidBinary extends AbstractBuildRule
    implements SupportsInputBasedRuleKey,
        HasDeclaredAndExtraDeps,
        HasClasspathDeps,
        HasClasspathEntries,
        HasRuntimeDeps,
        HasInstallableApk,
        HasInstallHelpers {
  static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  private final Keystore keystore;

  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  private final int optimizationPasses;
  private final Optional<SourcePath> proguardConfig;

  private final Optional<List<String>> proguardJvmArgs;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ResourceFilter resourceFilter;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex;

  private final AndroidGraphEnhancementResult enhancementResult;
  private final ManifestEntries manifestEntries;
  private final boolean skipProguard;
  private final Tool javaRuntimeLauncher;
  private final boolean isCacheable;

  private final Optional<BuildRule> moduleVerification;
  private final Optional<ExopackageInfo> exopackageInfo;
  private final SourcePath manifestPath;

  private final BuildRuleParams buildRuleParams;

  @AddToRuleKey private final AndroidBinaryBuildable buildable;
  @AddToRuleKey private final AndroidBinaryOptimizer optimizer;

  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  // TODO(cjhopman): What's the difference between shouldProguard and skipProguard?
  AndroidBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidSdkLocation androidSdkLocation,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Optional<List<String>> proguardJvmArgs,
      Keystore keystore,
      DexSplitMode dexSplitMode,
      Set<BuildTarget> buildTargetsToExcludeFromDex,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      int proguardOptimizationPasses,
      Optional<SourcePath> proguardConfig,
      boolean skipProguard,
      Optional<RedexOptions> redexOptions,
      ResourceCompressionMode resourceCompressionMode,
      Set<TargetCpuType> cpuFilters,
      ResourceFilter resourceFilter,
      EnumSet<ExopackageMode> exopackageModes,
      Supplier<ImmutableSet<JavaLibrary>> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      int xzCompressionLevel,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      Optional<CompressionAlgorithm> assetCompressionAlgorithm,
      ManifestEntries manifestEntries,
      Tool javaRuntimeLauncher,
      boolean isCacheable,
      Optional<BuildRule> moduleVerification,
      DexFilesInfo dexFilesInfo,
      NativeFilesInfo nativeFilesInfo,
      ResourceFilesInfo resourceFilesInfo,
      ImmutableSortedSet<APKModule> apkModules,
      Optional<ExopackageInfo> exopackageInfo) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkArgument(params.getExtraDeps().get().isEmpty());
    this.proguardJvmArgs = proguardJvmArgs;
    this.keystore = keystore;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.buildTargetsToExcludeFromDex = ImmutableSet.copyOf(buildTargetsToExcludeFromDex);
    this.sdkProguardConfig = sdkProguardConfig;
    this.optimizationPasses = proguardOptimizationPasses;
    this.proguardConfig = proguardConfig;
    this.resourceCompressionMode = resourceCompressionMode;
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.resourceFilter = resourceFilter;
    this.exopackageModes = exopackageModes;
    this.rulesToExcludeFromDex = rulesToExcludeFromDex;
    this.enhancementResult = enhancementResult;
    this.skipProguard = skipProguard;
    this.manifestEntries = manifestEntries;
    this.isCacheable = isCacheable;
    this.moduleVerification = moduleVerification;
    this.manifestPath = enhancementResult.getAndroidManifestPath();

    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      Preconditions.checkArgument(
          enhancementResult.getPreDexMergeSplitDex().isPresent(),
          "%s specified exopackage without pre-dexing and split dex, which is invalid.",
          getBuildTarget());
      Preconditions.checkArgument(
          dexSplitMode.getDexStore() == DexStore.JAR,
          "%s specified exopackage with secondary dex mode %s, "
              + "which is invalid.  (Only JAR is allowed.)",
          getBuildTarget(),
          dexSplitMode.getDexStore());
    }

    if (ExopackageMode.enabledForResources(exopackageModes)
        && !(ExopackageMode.enabledForSecondaryDexes(exopackageModes)
            && ExopackageMode.enabledForNativeLibraries(exopackageModes))) {
      throw new HumanReadableException(
          "Invalid exopackage_modes for android_binary %s. %s requires %s and %s",
          getBuildTarget().getUnflavoredBuildTarget(),
          ExopackageMode.RESOURCES,
          ExopackageMode.NATIVE_LIBRARY,
          ExopackageMode.SECONDARY_DEX);
    }

    this.buildable =
        new AndroidApkBuildable(
            getBuildTarget(),
            getProjectFilesystem(),
            androidSdkLocation,
            keystore.getPathToStore(),
            keystore.getPathToPropertiesFile(),
            exopackageModes,
            xzCompressionLevel,
            packageAssetLibraries,
            compressAssetLibraries,
            assetCompressionAlgorithm,
            javaRuntimeLauncher,
            enhancementResult.getAndroidManifestPath(),
            dexFilesInfo,
            nativeFilesInfo,
            resourceFilesInfo,
            apkModules,
            enhancementResult.getModuleResourceApkPaths(),
            Optional.empty(),
            APK);

    this.optimizer =
        new AndroidApkOptimizer(
            getBuildTarget(),
            getProjectFilesystem(),
            androidSdkLocation,
            androidPlatformTarget,
            keystore.getPathToStore(),
            keystore.getPathToPropertiesFile(),
            redexOptions,
            packageAssetLibraries,
            compressAssetLibraries,
            assetCompressionAlgorithm,
            resourceCompressionMode.isCompressResources());
    this.exopackageInfo = exopackageInfo;

    params =
        new BuildRuleParams(
            ImmutableSortedSet::of,
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
            params.getTargetGraphOnlyDeps());
    this.buildRuleParams = params;

    this.transitiveClasspathDepsSupplier =
        createTransitiveClasspathDepsSupplier(ruleFinder, enhancementResult);
  }

  private static Supplier<ImmutableSet<JavaLibrary>> createTransitiveClasspathDepsSupplier(
      SourcePathRuleFinder ruleFinder, AndroidGraphEnhancementResult enhancementResult) {
    return Suppliers.memoize(
        () ->
            JavaLibraryClasspathProvider.getClasspathDeps(
                ruleFinder
                    .filterBuildRuleInputs(enhancementResult.getClasspathEntriesToDex().stream())
                    .collect(ImmutableSet.toImmutableSet())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildRuleParams.getBuildDeps();
  }

  @Override
  public SortedSet<BuildRule> getDeclaredDeps() {
    return buildRuleParams.getDeclaredDeps().get();
  }

  @Override
  public SortedSet<BuildRule> deprecatedGetExtraDeps() {
    return buildRuleParams.getExtraDeps().get();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return buildRuleParams.getTargetGraphOnlyDeps();
  }

  public Supplier<ImmutableSet<JavaLibrary>> getRulesToExcludeFromDex() {
    return rulesToExcludeFromDex;
  }

  public ImmutableSet<BuildTarget> getBuildTargetsToExcludeFromDex() {
    return buildTargetsToExcludeFromDex;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  public boolean getSkipProguard() {
    return skipProguard;
  }

  public ResourceCompressionMode getResourceCompressionMode() {
    return resourceCompressionMode;
  }

  public ImmutableSet<TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
  }

  public ResourceFilter getResourceFilter() {
    return resourceFilter;
  }

  ProGuardObfuscateStep.SdkProguardType getSdkProguardConfig() {
    return sdkProguardConfig;
  }

  public int getOptimizationPasses() {
    return optimizationPasses;
  }

  public Optional<List<String>> getProguardJvmArgs() {
    return proguardJvmArgs;
  }

  public ManifestEntries getManifestEntries() {
    return manifestEntries;
  }

  Tool getJavaRuntimeLauncher() {
    return javaRuntimeLauncher;
  }

  @VisibleForTesting
  AndroidGraphEnhancementResult getEnhancementResult() {
    return enhancementResult;
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public ApkInfo getApkInfo() {
    return ImmutableApkInfo.of(manifestPath, getSourcePathToOutput(), exopackageInfo);
  }

  @Override
  public Stream<BuildTarget> getInstallHelpers() {
    return Stream.of(
        getBuildTarget().withFlavors(AndroidBinaryInstallGraphEnhancer.INSTALL_FLAVOR));
  }

  @Override
  public boolean isCacheable() {
    return isCacheable;
  }

  @Override
  public boolean inputBasedRuleKeyIsEnabled() {
    return !exopackageModes.isEmpty();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.<Step>builder()
        .addAll(buildable.getBuildSteps(context, buildableContext))
        .addAll(optimizer.getBuildSteps(context, buildableContext))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        AndroidBinaryPathUtility.getFinalApkPath(getProjectFilesystem(), getBuildTarget(), APK));
  }

  public AndroidPackageableCollection getAndroidPackageableCollection() {
    return enhancementResult.getPackageableCollection();
  }

  public Keystore getKeystore() {
    return keystore;
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    // This is used primarily for buck audit classpath.
    return JavaLibraryClasspathProvider.getClasspathsFromLibraries(getTransitiveClasspathDeps());
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    // The apk has no exported deps or classpath contributions of its own
    return ImmutableSet.of();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return RichStream.from(moduleVerification)
        .map(BuildRule::getBuildTarget)
        .concat(HasInstallableApkSupport.getRuntimeDepsForInstallableApk(this, buildRuleResolver));
  }

  @Override
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(getTransitiveClasspathDeps());
  }
}
