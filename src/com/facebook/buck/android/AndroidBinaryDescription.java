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

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.Javac;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class AndroidBinaryDescription implements Description<AndroidBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_binary");

  /**
   * By default, assume we have 5MB of linear alloc,
   * 1MB of which is taken up by the framework, so that leaves 4MB.
   */
  private static final long DEFAULT_LINEAR_ALLOC_HARD_LIMIT = 4 * 1024 * 1024;

  private static final BuildTargetParser BUILD_TARGET_PARSER = new BuildTargetParser();
  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "exe", new ExecutableMacroExpander(BUILD_TARGET_PARSER),
              "location", new LocationMacroExpander(BUILD_TARGET_PARSER)));

  private final Javac javac;
  private final JavacOptions javacOptions;
  private final ProGuardConfig proGuardConfig;
  private final ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;

  public AndroidBinaryDescription(
      Javac javac,
      JavacOptions javacOptions,
      ProGuardConfig proGuardConfig,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms) {
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.proGuardConfig = proGuardConfig;
    this.nativePlatforms = nativePlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AndroidBinary createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    BuildRule keystore = resolver.getRule(args.keystore);
    if (!(keystore instanceof Keystore)) {
      throw new HumanReadableException(
          "In %s, keystore='%s' must be a keystore() but was %s().",
          params.getBuildTarget(),
          keystore.getFullyQualifiedName(),
          keystore.getType().getName());
    }

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.androidSdkProguardConfig.or(ProGuardObfuscateStep.SdkProguardType.DEFAULT);

    // If the old boolean version of this argument was specified, make sure the new form
    // was not specified, and allow the old form to override the default.
    if (args.useAndroidProguardConfigWithOptimizations.isPresent()) {
      Preconditions.checkArgument(
          !args.androidSdkProguardConfig.isPresent(),
          "The deprecated use_android_proguard_config_with_optimizations parameter" +
              " cannot be used with android_sdk_proguard_config.");
      androidSdkProguardConfig = args.useAndroidProguardConfigWithOptimizations.or(false)
          ? ProGuardObfuscateStep.SdkProguardType.OPTIMIZED
          : ProGuardObfuscateStep.SdkProguardType.DEFAULT;
    }

    EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
    if (args.exopackageModes.isPresent() && !args.exopackageModes.get().isEmpty()) {
      exopackageModes = EnumSet.copyOf(args.exopackageModes.get());
    } else if (args.exopackage.or(false)) {
      exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
    }

    DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

    boolean allowNonExistentRule =
          false;
    ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = BuildRules.toBuildRulesFor(
        params.getBuildTarget(),
        resolver,
        args.noDx.or(ImmutableSet.<BuildTarget>of()),
        allowNonExistentRule);
    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        FluentIterable.from(buildRulesToExcludeFromDex)
            .filter(JavaLibrary.class)
            .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);

    PackageType packageType = getPackageType(args);
    boolean shouldPreDex = !args.disablePreDex.or(false) &&
        PackageType.DEBUG.equals(packageType) &&
        !args.preprocessJavaClassesBash.isPresent();

    ResourceCompressionMode compressionMode = getCompressionMode(args);
    ResourceFilter resourceFilter =
        new ResourceFilter(args.resourceFilter.or(ImmutableList.<String>of()));

    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        params,
        resolver,
        compressionMode,
        resourceFilter,
        args.manifest,
        packageType,
        ImmutableSet.copyOf(args.cpuFilters.get()),
        args.buildStringSourceMap.or(false),
        shouldPreDex,
        AndroidBinary.getPrimaryDexPath(params.getBuildTarget()),
        dexSplitMode,
        ImmutableSet.copyOf(args.noDx.or(ImmutableSet.<BuildTarget>of())),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        javac,
        javacOptions,
        exopackageModes,
        (Keystore) keystore,
        args.buildConfigValues.get(),
        args.buildConfigValuesFile,
        nativePlatforms);
    AndroidGraphEnhancementResult result =
        graphEnhancer.createAdditionalBuildables();

    return new AndroidBinary(
        params.copyWithExtraDeps(result.finalDeps()),
        new SourcePathResolver(resolver),
        proGuardConfig.getProguardJarOverride(),
        proGuardConfig.getProguardMaxHeapSize(),
        args.manifest,
        args.target,
        (Keystore) keystore,
        packageType,
        dexSplitMode,
        args.noDx.or(ImmutableSet.<BuildTarget>of()),
        androidSdkProguardConfig,
        args.optimizationPasses,
        args.proguardConfig,
        compressionMode,
        args.cpuFilters.get(),
        resourceFilter,
        exopackageModes,
        resolver.getAllRules(
            args.preprocessJavaClassesDeps.or(ImmutableSortedSet.<BuildTarget>of())),
        MACRO_HANDLER.getExpander(
            params.getBuildTarget(),
            resolver,
            params.getProjectFilesystem()),
        args.preprocessJavaClassesBash,
        rulesToExcludeFromDex,
        result);
  }

  private DexSplitMode createDexSplitMode(Arg args, EnumSet<ExopackageMode> exopackageModes) {
    // Exopackage builds default to JAR, otherwise, default to RAW.
    DexStore defaultDexStore = ExopackageMode.enabledForSecondaryDexes(exopackageModes)
        ? DexStore.JAR
        : DexStore.RAW;
    DexSplitStrategy dexSplitStrategy = args.minimizePrimaryDexSize.or(false)
        ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
        : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.useSplitDex.or(false),
        dexSplitStrategy,
        args.dexCompression.or(defaultDexStore),
        args.useLinearAllocSplitDex.or(false),
        args.linearAllocHardLimit.or(DEFAULT_LINEAR_ALLOC_HARD_LIMIT),
        args.primaryDexPatterns.or(ImmutableList.<String>of()),
        args.primaryDexClassesFile,
        args.primaryDexScenarioFile,
        args.primaryDexScenarioOverflowAllowed.or(false));
  }

  private PackageType getPackageType(Arg args) {
    if (!args.packageType.isPresent()) {
      return PackageType.DEBUG;
    }
    return PackageType.valueOf(args.packageType.get().toUpperCase());
  }

  private ResourceCompressionMode getCompressionMode(Arg args) {
    if (!args.resourceCompression.isPresent()) {
      return ResourceCompressionMode.DISABLED;
    }
    return ResourceCompressionMode.valueOf(args.resourceCompression.get().toUpperCase());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public SourcePath manifest;
    public String target;
    public BuildTarget keystore;
    public Optional<String> packageType;
    @Hint(isDep = false) public Optional<Set<BuildTarget>> noDx;
    public Optional<Boolean> useSplitDex;
    public Optional<Boolean> useLinearAllocSplitDex;
    public Optional<Boolean> minimizePrimaryDexSize;
    public Optional<Boolean> disablePreDex;
    // TODO(natthu): mark this as deprecated.
    public Optional<Boolean> exopackage;
    public Optional<Set<ExopackageMode>> exopackageModes;
    public Optional<DexStore> dexCompression;
    public Optional<ProGuardObfuscateStep.SdkProguardType> androidSdkProguardConfig;
    public Optional<Boolean> useAndroidProguardConfigWithOptimizations;
    public Optional<Integer> optimizationPasses;
    public Optional<SourcePath> proguardConfig;
    public Optional<String> resourceCompression;
    public Optional<List<String>> primaryDexPatterns;
    public Optional<SourcePath> primaryDexClassesFile;
    public Optional<SourcePath> primaryDexScenarioFile;
    public Optional<Boolean> primaryDexScenarioOverflowAllowed;
    public Optional<Long> linearAllocHardLimit;
    public Optional<List<String>> resourceFilter;
    public Optional<Boolean> buildStringSourceMap;
    public Optional<Set<TargetCpuType>> cpuFilters;
    public Optional<ImmutableSortedSet<BuildTarget>> preprocessJavaClassesDeps;
    public Optional<String> preprocessJavaClassesBash;

    /** This will never be absent after this Arg is populated. */
    public Optional<BuildConfigFields> buildConfigValues;

    public Optional<SourcePath> buildConfigValuesFile;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
