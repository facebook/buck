/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidBinaryRule.PackageType;
import com.facebook.buck.android.AndroidBinaryRule.TargetCpuType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.UberRDotJava.ResourceCompressionMode;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildRuleBuilderParams;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidBinaryGraphEnhancer {

  private static final String DEX_FLAVOR = "dex";
  private static final String DEX_MERGE_FLAVOR = "dex_merge";
  private static final String UBER_R_DOT_JAVA_FLAVOR = "uber_r_dot_java";
  private static final String AAPT_PACKAGE_FLAVOR = "aapt_package";

  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final AbstractBuildRuleBuilderParams buildRuleBuilderParams;
  private final ImmutableSortedSet.Builder<BuildRule> totalDeps;

  AndroidBinaryGraphEnhancer(BuildRuleParams originalParams) {
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
    this.buildRuleBuilderParams = new DefaultBuildRuleBuilderParams(
        originalParams.getPathRelativizer(),
        originalParams.getRuleKeyBuilderFactory());
    this.totalDeps = ImmutableSortedSet.naturalOrder();

    totalDeps.addAll(originalDeps);
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   * <p>
   * This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  DexEnhancementResult createDepsForPreDexing(
      BuildRuleResolver ruleResolver,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildRulesToExcludeFromDex,
      UberRDotJava uberRDotJava) {
    ImmutableSet.Builder<IntermediateDexRule> preDexDeps = ImmutableSet.builder();
    ImmutableSet<JavaLibraryRule> transitiveJavaDeps = Classpaths
        .getClasspathEntries(originalDeps).keySet();
    for (JavaLibraryRule javaLibraryRule : transitiveJavaDeps) {
      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibraryRule.getPathToOutputFile() == null) {
        continue;
      }

      // If the rule is in the no_dx list, then do not pre-dex it.
      if (buildRulesToExcludeFromDex.contains(javaLibraryRule.getBuildTarget())) {
        continue;
      }

      // See whether the corresponding IntermediateDexRule has already been added to the
      // ruleResolver.
      BuildTarget originalTarget = javaLibraryRule.getBuildTarget();
      BuildTarget preDexTarget = new BuildTarget(originalTarget.getBaseName(),
          originalTarget.getShortName(),
          DEX_FLAVOR);
      IntermediateDexRule preDexRule = (IntermediateDexRule) ruleResolver.get(preDexTarget);
      if (preDexRule != null) {
        preDexDeps.add(preDexRule);
        continue;
      }

      // Create the IntermediateDexRule and add it to both the ruleResolver and preDexDeps.
      IntermediateDexRule preDex = ruleResolver.buildAndAddToIndex(
          IntermediateDexRule
              .newPreDexBuilder(buildRuleBuilderParams)
              .setBuildTarget(preDexTarget)
              .setJavaLibraryRuleToDex(javaLibraryRule)
              .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));
      preDexDeps.add(preDex);
    }

    ImmutableSet<IntermediateDexRule> allPreDexDeps = preDexDeps.build();

    BuildTarget buildTargetForDexMerge = createBuildTargetWithFlavor(DEX_MERGE_FLAVOR);
    BuildRule preDexMergeBuildRule = ruleResolver.buildAndAddToIndex(
        PreDexMerge
            .newPreDexMergeBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForDexMerge)
            .setPrimaryDexPath(primaryDexPath)
            .setDexSplitMode(dexSplitMode)
            .setPreDexDeps(allPreDexDeps)
            .setUberRDotJava(uberRDotJava));
    PreDexMerge preDexMerge = (PreDexMerge) preDexMergeBuildRule.getBuildable();

    totalDeps.add(preDexMergeBuildRule);

    return new DexEnhancementResult(Optional.of(preDexMerge));
  }

  AaptEnhancementResult addBuildablesToCreateAaptResources(BuildRuleResolver ruleResolver,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourceFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      SourcePath manifest,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean rDotJavaNeedsDexing) {
    BuildTarget buildTargetForResources = createBuildTargetWithFlavor(UBER_R_DOT_JAVA_FLAVOR);
    BuildRule uberRDotJavaBuildRule = ruleResolver.buildAndAddToIndex(
        UberRDotJava
            .newUberRDotJavaBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForResources)
            .setResourceCompressionMode(resourceCompressionMode)
            .setResourceFilter(resourceFilter)
            .setAndroidResourceDepsFinder(androidResourceDepsFinder)
            .setRDotJavaNeedsDexing(rDotJavaNeedsDexing));
    UberRDotJava uberRDotJava = (UberRDotJava) uberRDotJavaBuildRule.getBuildable();

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    BuildRule aaptPackageResourcesBuildRule = ruleResolver.buildAndAddToIndex(
        AaptPackageResources
            .newAaptPackageResourcesBuildableBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForAapt)
            .setAllParams(manifest,
                uberRDotJava,
                androidResourceDepsFinder.getAndroidTransitiveDependencies().nativeTargetsWithAssets,
                packageType,
                cpuFilters));
    AaptPackageResources aaptPackageResources =
        (AaptPackageResources) aaptPackageResourcesBuildRule.getBuildable();

    totalDeps
        .add(uberRDotJavaBuildRule)
        .add(aaptPackageResourcesBuildRule)
        .build();

    return new AaptEnhancementResult(uberRDotJava, aaptPackageResources);
  }

  /**
   * This should be called after all "createDeps" methods to get the total set of dependencies
   * that the final AndroidBinaryRule should have.
   *
   * @return All dependencies for the AndroidBinaryRule.
   */
  ImmutableSortedSet<BuildRule> getTotalDeps() {
    return totalDeps.build();
  }

  static class AaptEnhancementResult {
    private final UberRDotJava uberRDotJava;
    private final AaptPackageResources aaptPackageResources;

    public AaptEnhancementResult(
        UberRDotJava uberRDotJava,
        AaptPackageResources aaptPackageBuildable) {
      this.uberRDotJava = uberRDotJava;
      this.aaptPackageResources = aaptPackageBuildable;
    }


    public UberRDotJava getUberRDotJava() {
      return uberRDotJava;
    }

    public AaptPackageResources getAaptPackageResources() {
      return aaptPackageResources;
    }
  }

  static class DexEnhancementResult {
    private final Optional<PreDexMerge> preDexMerge;

    DexEnhancementResult(Optional<PreDexMerge> preDexMerge) {
      this.preDexMerge = preDexMerge;
    }

    public Optional<PreDexMerge> getPreDexMerge() {
      return preDexMerge;
    }
  }

  private BuildTarget createBuildTargetWithFlavor(String flavor) {
    return new BuildTarget(originalBuildTarget.getBaseName(),
        originalBuildTarget.getShortName(),
        flavor);
  }
}
