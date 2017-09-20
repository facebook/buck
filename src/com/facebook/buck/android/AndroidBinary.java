/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.TargetCpuType;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredAndExtraDeps;
import com.facebook.buck.rules.HasInstallHelpers;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
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
        HasClasspathEntries,
        HasRuntimeDeps,
        HasInstallableApk,
        HasInstallHelpers {
  static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  /**
   * This list of package types is taken from the set of targets that the default build.xml provides
   * for Android projects.
   *
   * <p>Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
   */
  enum PackageType {
    DEBUG,
    INSTRUMENTED,
    RELEASE,
    TEST,
    ;
  }

  enum ExopackageMode {
    SECONDARY_DEX(1),
    NATIVE_LIBRARY(2),
    RESOURCES(4),
    ;

    private final int code;

    ExopackageMode(int code) {
      this.code = code;
    }

    public static boolean enabledForSecondaryDexes(EnumSet<ExopackageMode> modes) {
      return modes.contains(SECONDARY_DEX);
    }

    public static boolean enabledForNativeLibraries(EnumSet<ExopackageMode> modes) {
      return modes.contains(NATIVE_LIBRARY);
    }

    public static boolean enabledForResources(EnumSet<ExopackageMode> modes) {
      return modes.contains(RESOURCES);
    }

    public static int toBitmask(EnumSet<ExopackageMode> modes) {
      int bitmask = 0;
      for (ExopackageMode mode : modes) {
        bitmask |= mode.code;
      }
      return bitmask;
    }
  }

  enum RelinkerMode {
    ENABLED,
    DISABLED,
    ;
  }

  enum AaptMode {
    AAPT1,
    AAPT2,
    ;
  }

  private final Keystore keystore;

  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  private final Optional<Integer> optimizationPasses;
  private final Optional<SourcePath> proguardConfig;
  private final SourcePathRuleFinder ruleFinder;

  private final Optional<List<String>> proguardJvmArgs;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ResourceFilter resourceFilter;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex;

  private final AndroidGraphEnhancementResult enhancementResult;
  private final ManifestEntries manifestEntries;
  private final boolean skipProguard;
  private final Tool javaRuntimeLauncher;
  private final boolean isCacheable;
  private final Optional<SourcePath> appModularityResult;

  private final BuildRuleParams buildRuleParams;

  @AddToRuleKey private final AndroidBinaryBuildable buildable;

  AndroidBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> proguardJarOverride,
      String proguardMaxHeapSize,
      Optional<List<String>> proguardJvmArgs,
      Optional<String> proguardAgentPath,
      Keystore keystore,
      DexSplitMode dexSplitMode,
      Set<BuildTarget> buildTargetsToExcludeFromDex,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      Optional<Integer> proguardOptimizationPasses,
      Optional<SourcePath> proguardConfig,
      boolean skipProguard,
      Optional<RedexOptions> redexOptions,
      ResourceCompressionMode resourceCompressionMode,
      Set<TargetCpuType> cpuFilters,
      ResourceFilter resourceFilter,
      EnumSet<ExopackageMode> exopackageModes,
      Optional<Arg> preprocessJavaClassesBash,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      boolean reorderClassesIntraDex,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile,
      Optional<Integer> xzCompressionLevel,
      ListeningExecutorService dxExecutorService,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      ManifestEntries manifestEntries,
      Tool javaRuntimeLauncher,
      Optional<String> dxMaxHeapSize,
      boolean isCacheable,
      Optional<SourcePath> appModularityResult) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkArgument(params.getExtraDeps().get().isEmpty());
    this.ruleFinder = ruleFinder;
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
    this.appModularityResult = appModularityResult;

    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      Preconditions.checkArgument(
          enhancementResult.getPreDexMerge().isPresent(),
          "%s specified exopackage without pre-dexing, which is invalid.",
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
        new AndroidBinaryBuildable(
            getBuildTarget(),
            getProjectFilesystem(),
            keystore.getPathToStore(),
            keystore.getPathToPropertiesFile(),
            dexSplitMode,
            sdkProguardConfig,
            optimizationPasses,
            proguardConfig,
            proguardJarOverride,
            redexOptions,
            proguardMaxHeapSize,
            proguardJvmArgs,
            proguardAgentPath,
            this.cpuFilters,
            exopackageModes,
            preprocessJavaClassesBash,
            reorderClassesIntraDex,
            dexReorderToolFile,
            dexReorderDataDumpFile,
            rulesToExcludeFromDex,
            enhancementResult,
            dxExecutorService,
            xzCompressionLevel,
            packageAssetLibraries,
            compressAssetLibraries,
            skipProguard,
            javaRuntimeLauncher,
            enhancementResult.getAndroidManifestPath(),
            enhancementResult.getPrimaryResourcesApkPath(),
            enhancementResult.getPrimaryApkAssetZips(),
            enhancementResult.getSourcePathToAaptGeneratedProguardConfigFile(),
            dxMaxHeapSize,
            enhancementResult.getProguardConfigs(),
            resourceCompressionMode.isCompressResources(),
            this.appModularityResult);
    params =
        params.withExtraDeps(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(MoreCollectors.toImmutableSortedSet()));
    this.buildRuleParams = params;
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

  public ImmutableSortedSet<JavaLibrary> getRulesToExcludeFromDex() {
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

  public Optional<Integer> getOptimizationPasses() {
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

  @VisibleForTesting
  AndroidBinaryBuildable getBuildableForTests() {
    return buildable;
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public ApkInfo getApkInfo() {
    return ApkInfo.builder()
        .setApkPath(getSourcePathToOutput())
        .setManifestPath(getManifestPath())
        .setExopackageInfo(getExopackageInfo())
        .build();
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
    return buildable.getBuildSteps(context, buildableContext);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), buildable.getFinalApkPath());
  }

  public AndroidPackageableCollection getAndroidPackageableCollection() {
    return enhancementResult.getPackageableCollection();
  }

  public Keystore getKeystore() {
    return keystore;
  }

  private SourcePath getManifestPath() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), buildable.getManifestPath());
  }

  private Optional<ExopackageInfo> getExopackageInfo() {
    boolean shouldInstall = false;

    ExopackageInfo.Builder builder = ExopackageInfo.builder();
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      PreDexMerge preDexMerge = enhancementResult.getPreDexMerge().get();
      builder.setDexInfo(
          ExopackageInfo.DexInfo.of(
              preDexMerge.getMetadataTxtSourcePath(), preDexMerge.getDexDirectorySourcePath()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && enhancementResult.getCopyNativeLibraries().isPresent()) {
      CopyNativeLibraries copyNativeLibraries =
          Preconditions.checkNotNull(
              enhancementResult
                  .getCopyNativeLibraries()
                  .get()
                  .get(enhancementResult.getAPKModuleGraph().getRootAPKModule()));
      builder.setNativeLibsInfo(
          ExopackageInfo.NativeLibsInfo.of(
              copyNativeLibraries.getSourcePathToMetadataTxt(),
              copyNativeLibraries.getSourcePathToAllLibsDir()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForResources(exopackageModes)) {
      Preconditions.checkState(!enhancementResult.getExoResources().isEmpty());
      builder.setResourcesInfo(
          ExopackageInfo.ResourcesInfo.of(
              ImmutableList.<SourcePath>builder()
                  .addAll(enhancementResult.getExoResources())
                  .add(
                      new ExplicitBuildTargetSourcePath(
                          getBuildTarget(), buildable.getMergedThirdPartyJarsPath()))
                  .build()));
      shouldInstall = true;
    } else {
      Preconditions.checkState(enhancementResult.getExoResources().isEmpty());
    }

    if (!shouldInstall) {
      return Optional.empty();
    }

    ExopackageInfo exopackageInfo = builder.build();
    return Optional.of(exopackageInfo);
  }

  public SortedSet<BuildRule> getClasspathDeps() {
    return getDeclaredDeps();
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    // This is used primarily for buck audit classpath.
    return JavaLibraryClasspathProvider.getClasspathsFromLibraries(getTransitiveClasspathDeps());
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return JavaLibraryClasspathProvider.getClasspathDeps(
        enhancementResult
            .getClasspathEntriesToDex()
            .stream()
            .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
            .collect(MoreCollectors.toImmutableSet()));
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
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    Stream.Builder<Stream<BuildTarget>> deps = Stream.builder();
    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && enhancementResult.getCopyNativeLibraries().isPresent()) {
      deps.add(
          enhancementResult
              .getCopyNativeLibraries()
              .get()
              .values()
              .stream()
              .map(BuildRule::getBuildTarget));
    }
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      deps.add(
          Optionals.toStream(enhancementResult.getPreDexMerge()).map(BuildRule::getBuildTarget));
    }
    if (ExopackageMode.enabledForResources(exopackageModes)) {
      deps.add(
          enhancementResult
              .getExoResources()
              .stream()
              .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
              .map(BuildRule::getBuildTarget));
    }
    return deps.build().reduce(Stream.empty(), Stream::concat);
  }
}
