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

import static com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import static com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Set;

public class AndroidBinaryBuilder {

  private AndroidBinaryBuilder() {
    // Utility class.
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private BuildTarget buildTarget;
    private SourcePath manifest;
    private String target;
    private Optional<String> preprocessJavaClassesBash = Optional.absent();
    private Set<BuildRule> preprocessJavaClassesDeps = ImmutableSet.of();
    private ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of();
    private Keystore keystore;
    private AndroidBinary.PackageType packageType = AndroidBinary.PackageType.DEBUG;
    private DexSplitMode dexSplitMode = DexSplitMode.NO_SPLIT;
    private Set<BuildTarget> buildTargetsToExcludeFromDex = ImmutableSet.of();
    private boolean useAndroidProguardConfig = false;
    private Optional<Integer> proguardOptimizationPasses = Optional.absent();
    private Optional<SourcePath> proguardConfig = Optional.absent();
    private ResourceCompressionMode resourceCompressionMode = ResourceCompressionMode.DISABLED;
    private Set<AndroidBinary.TargetCpuType> cpuFilters = ImmutableSet.of();
    private ResourceFilter resourceFilter = ResourceFilter.EMPTY_FILTER;
    private boolean buildStringSourceMap = false;
    private boolean disablePreDex = false;
    private boolean exopackage = false;

    public AndroidBinary build() {
      return new AndroidBinary(
          new FakeBuildRuleParamsBuilder(buildTarget).setDeps(originalDeps).build(),
          JavacOptions.DEFAULTS,
          /* proguardJarOverride */ Optional.<Path>absent(),
          manifest,
          target,
          originalDeps,
          keystore,
          packageType,
          dexSplitMode,
          buildTargetsToExcludeFromDex,
          useAndroidProguardConfig,
          proguardOptimizationPasses,
          proguardConfig,
          resourceCompressionMode,
          cpuFilters,
          resourceFilter,
          buildStringSourceMap,
          disablePreDex,
          exopackage,
          preprocessJavaClassesDeps,
          preprocessJavaClassesBash);
    }

    public BuildRule build(BuildRuleResolver ruleResolver) {
      return ruleResolver.addToIndex(
          new DescribedRule(
              AndroidBinaryDescription.TYPE,
              build(),
              new FakeBuildRuleParamsBuilder(buildTarget).setDeps(originalDeps).build()));
    }

    public Builder setBuildTarget(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
      return this;
    }

    public Builder setManifest(SourcePath manifest) {
      this.manifest = manifest;
      return this;
    }

    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    public Builder setOriginalDeps(ImmutableSortedSet<BuildRule> originalDeps) {
      this.originalDeps = originalDeps;
      return this;
    }

    public Builder setKeystore(Keystore keystore) {
      this.keystore = keystore;
      return this;
    }

    public Builder setDexSplitMode(DexSplitMode dexSplitMode) {
      this.dexSplitMode = dexSplitMode;
      return this;
    }

    public Builder setBuildTargetsToExcludeFromDex(Set<BuildTarget> buildTargetsToExcludeFromDex) {
      this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
      return this;
    }

    public Builder setProguardConfig(Optional<SourcePath> proguardConfig) {
      this.proguardConfig = proguardConfig;
      return this;
    }

    public Builder setResourceCompressionMode(ResourceCompressionMode resourceCompressionMode) {
      this.resourceCompressionMode = resourceCompressionMode;
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = resourceFilter;
      return this;
    }
  }
}
