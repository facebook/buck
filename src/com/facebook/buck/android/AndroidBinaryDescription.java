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

import static com.facebook.buck.android.AndroidBinary.PackageType;
import static com.facebook.buck.android.AndroidBinary.TargetCpuType;
import static com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import static com.facebook.buck.dalvik.ZipSplitter.DexSplitStrategy;

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class AndroidBinaryDescription implements Description<AndroidBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_binary");

  /**
   * By default, assume we have 5MB of linear alloc,
   * 1MB of which is taken up by the framework, so that leaves 4MB.
   */
  private static final long DEFAULT_LINEAR_ALLOC_HARD_LIMIT = 4 * 1024 * 1024;

  public final JavacOptions javacOptions;
  public final Optional<Path> proguardJarOverride;

  public AndroidBinaryDescription(JavacOptions javacOptions, Optional<Path> proguardJarOverride) {
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.proguardJarOverride = Preconditions.checkNotNull(proguardJarOverride);
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
  public AndroidBinary createBuildable(BuildRuleParams params, Arg args) {
    if (!(args.keystore.getBuildable() instanceof Keystore)) {
      throw new HumanReadableException(
          "In %s, keystore='%s' must be a keystore() but was %s().",
          params.getBuildTarget(),
          args.keystore.getFullyQualifiedName(),
          args.keystore.getType().getName());
    }

    DexSplitMode dexSplitMode = createDexSplitMode(args);
    return new AndroidBinary(
        params,
        javacOptions,
        proguardJarOverride,
        args.manifest,
        args.target,
        args.deps.get(),
        (Keystore) args.keystore.getBuildable(),
        getPackageType(args),
        dexSplitMode,
        args.noDx.or(ImmutableSet.<BuildTarget>of()),
        args.useAndroidProguardConfigWithOptimizations.or(false),
        args.optimizationPasses,
        args.proguardConfig,
        getCompressionMode(args),
        getCpuFilters(args),
        new FilterResourcesStep.ResourceFilter(args.resourceFilter.or(ImmutableList.<String>of())),
        args.buildStringSourceMap.or(false),
        args.disablePreDex.or(false),
        args.exopackage.or(false),
        args.preprocessJavaClassesDeps.or(ImmutableSet.<BuildRule>of()),
        args.preprocessJavaClassesBash);
  }

  private DexSplitMode createDexSplitMode(Arg args) {
    DexStore dexStore = "xz".equals(args.dexCompression.or("jar"))
        ? DexStore.XZ
        : DexStore.JAR;
    DexSplitStrategy dexSplitStrategy = args.minimizePrimaryDexSize.or(false)
        ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
        : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.useSplitDex.or(false),
        dexSplitStrategy,
        dexStore,
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

  private Set<TargetCpuType> getCpuFilters(Arg args) {
    ImmutableSet.Builder<TargetCpuType> cpuFilters = ImmutableSet.builder();
    if (args.cpuFilters.isPresent()) {
      for (String cpuFilter : args.cpuFilters.get()) {
        cpuFilters.add(TargetCpuType.valueOf(cpuFilter.toUpperCase()));
      }
    }
    return cpuFilters.build();
  }

  public static class Arg implements ConstructorArg {
    public SourcePath manifest;
    public String target;
    public BuildRule keystore;
    public Optional<String> packageType;
    public Optional<Set<BuildTarget>> noDx;
    public Optional<Boolean> useSplitDex;
    public Optional<Boolean> useLinearAllocSplitDex;
    public Optional<Boolean> minimizePrimaryDexSize;
    public Optional<Boolean> disablePreDex;
    public Optional<Boolean> exopackage;
    public Optional<String> dexCompression;
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
    public Optional<List<String>> cpuFilters;
    public Optional<Set<BuildRule>> preprocessJavaClassesDeps;
    public Optional<String> preprocessJavaClassesBash;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
