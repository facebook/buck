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
import com.facebook.buck.android.UberRDotJavaBuildable.ResourceCompressionMode;
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class AndroidBinaryGraphEnhancer {

  private static final String DEX_FLAVOR = "dex";
  private static final String UBER_R_DOT_JAVA_FLAVOR = "uber_r_dot_java";
  private static final String AAPT_PACKAGE_FLAVOR = "aapt_package";

  private final BuildRuleParams originalParams;
  private final BuildTarget originalBuildTarget;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final ImmutableSet<BuildTarget> buildRulesToExcludeFromDex;
  private final AbstractBuildRuleBuilderParams buildRuleBuilderParams;

  AndroidBinaryGraphEnhancer(BuildRuleParams originalParams,
      ImmutableSet<BuildTarget> buildRulesToExcludeFromDex) {
    this.originalParams = Preconditions.checkNotNull(originalParams);
    this.originalBuildTarget = originalParams.getBuildTarget();
    this.originalDeps = originalParams.getDeps();
    this.buildRulesToExcludeFromDex = buildRulesToExcludeFromDex;
    this.buildRuleBuilderParams = new DefaultBuildRuleBuilderParams(
        originalParams.getPathRelativizer(),
        originalParams.getRuleKeyBuilderFactory());
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   * <p>
   * This method may modify {@code ruleResolver}, inserting new rules into its index.
   */
  ImmutableSet<IntermediateDexRule> createDepsForPreDexing(BuildRuleResolver ruleResolver) {
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
      IntermediateDexRule.Builder preDexBuilder = IntermediateDexRule
          .newPreDexBuilder(buildRuleBuilderParams)
          .setBuildTarget(preDexTarget)
          .setJavaLibraryRuleToDex(javaLibraryRule)
          .addVisibilityPattern(BuildTargetPattern.MATCH_ALL);
      IntermediateDexRule preDex = ruleResolver.buildAndAddToIndex(preDexBuilder);
      preDexDeps.add(preDex);
    }
    return preDexDeps.build();
  }

  Result addBuildablesToCreateAaptResources(BuildRuleResolver ruleResolver,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourceFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      SourcePath manifest,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableSet<IntermediateDexRule> preDexDeps) {
    BuildTarget originalBuildTarget = originalParams.getBuildTarget();
    BuildTarget buildTargetForResources = createBuildTargetWithFlavor(UBER_R_DOT_JAVA_FLAVOR);
    BuildRule uberRDotJavaBuildRule = ruleResolver.buildAndAddToIndex(
        UberRDotJavaBuildable
            .newUberRDotJavaBuildableBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForResources)
            .setAllParams(buildTargetForResources,
                resourceCompressionMode,
                resourceFilter,
                androidResourceDepsFinder));
    UberRDotJavaBuildable uberRDotJavaBuildable = (UberRDotJavaBuildable) uberRDotJavaBuildRule
        .getBuildable();

    // Create the AaptPackageResourcesBuildable.
    BuildTarget buildTargetForAapt = createBuildTargetWithFlavor(AAPT_PACKAGE_FLAVOR);
    BuildRule aaptPackageResourcesBuildRule = ruleResolver.buildAndAddToIndex(
        AaptPackageResources
            .newAaptPackageResourcesBuildableBuilder(buildRuleBuilderParams)
            .setBuildTarget(buildTargetForAapt)
            .setAllParams(manifest,
                uberRDotJavaBuildable,
                packageType,
                cpuFilters));
    AaptPackageResources aaptPackageResources =
        (AaptPackageResources) aaptPackageResourcesBuildRule.getBuildable();

    // Must create a new BuildRuleParams to supersede the one built by
    // createBuildRuleParams(ruleResolver).
    ImmutableSortedSet<BuildRule> totalDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(originalDeps)
        .addAll(preDexDeps)
        .add(uberRDotJavaBuildRule)
        .add(aaptPackageResourcesBuildRule)
        .build();
    BuildRuleParams buildRuleParams = new BuildRuleParams(originalBuildTarget,
        totalDeps,
        originalParams.getVisibilityPatterns(),
        originalParams.getPathRelativizer(),
        originalParams.getRuleKeyBuilderFactory());

    return new Result(buildRuleParams, uberRDotJavaBuildable, aaptPackageResources);
  }

  static class Result {
    private final BuildRuleParams params;
    private final UberRDotJavaBuildable uberRDotJavaBuildable;
    private final AaptPackageResources aaptPackageResources;

    public Result(BuildRuleParams params, UberRDotJavaBuildable uberRDotJavaBuildable,
        AaptPackageResources aaptPackageBuildable) {
      this.params = params;
      this.uberRDotJavaBuildable = uberRDotJavaBuildable;
      this.aaptPackageResources = aaptPackageBuildable;
    }

    public BuildRuleParams getParams() {
      return params;
    }

    public UberRDotJavaBuildable getUberRDotJavaBuildable() {
      return uberRDotJavaBuildable;
    }

    public AaptPackageResources getAaptPackageResources() {
      return aaptPackageResources;
    }
  }

  private BuildTarget createBuildTargetWithFlavor(String flavor) {
    return new BuildTarget(originalBuildTarget.getBaseName(),
        originalBuildTarget.getShortName(),
        flavor);
  }
}
