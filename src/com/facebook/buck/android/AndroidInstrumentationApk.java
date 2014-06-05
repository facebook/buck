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

import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyEnhancer;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;


/**
 * Apk that functions as a test package in Android.
 * <p>
 * Android's
 * <a href="http://developer.android.com/tools/testing/testing_android.html">
 *   Testing Fundamentals</a>
 * documentation includes a diagram that shows the relationship between an "application package" and
 * a "test package" when running a test. This corresponds to a test package. Note that a test
 * package has an interesting quirk where it is <em>compiled against</em> an application package,
 * but <em>must not include</em> the resources or Java classes of the application package.
 * Therefore, this class takes responsibility for making sure the appropriate bits are excluded.
 * Failing to do so will generate mysterious runtime errors when running the test.
 */
public class AndroidInstrumentationApk extends AndroidBinary implements DependencyEnhancer {

  private final BuildRule apkUnderTestAsRule;
  private final SourcePath manifest;
  private final AndroidBinary apkUnderTest;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final BuildRuleParams params;

  AndroidInstrumentationApk(
      BuildRuleParams buildRuleParams,
      SourcePath manifest,
      AndroidBinary apkUnderTest,
      BuildRule apkUnderTestAsRule,
      ImmutableSortedSet<BuildRule> originalDeps) {
    super(buildRuleParams,
        JavacOptions.DEFAULTS,
        /* proguardJarOverride */ Optional.<Path>absent(),
        manifest,
        apkUnderTest.getTarget(),
        originalDeps,
        apkUnderTest.getKeystore(),
        PackageType.INSTRUMENTED,
        // Do not split the test apk even if the tested apk is split
        DexSplitMode.NO_SPLIT,
        apkUnderTest.getBuildTargetsToExcludeFromDex(),
        apkUnderTest.isUseAndroidProguardConfigWithOptimizations(),
        apkUnderTest.getOptimizationPasses(),
        apkUnderTest.getProguardConfig(),
        apkUnderTest.getResourceCompressionMode(),
        apkUnderTest.getCpuFilters(),
        apkUnderTest.getResourceFilter(),
        /* buildStringSourceMap */ false,
        /* disablePreDex */ true,
        /* exopackage */ false,
        apkUnderTest.getPreprocessJavaClassesDeps(),
        apkUnderTest.getPreprocessJavaClassesBash());
    this.manifest = Preconditions.checkNotNull(manifest);
    this.apkUnderTest = Preconditions.checkNotNull(apkUnderTest);
    this.apkUnderTestAsRule = Preconditions.checkNotNull(apkUnderTestAsRule);
    this.originalDeps = Preconditions.checkNotNull(originalDeps);
    this.params = Preconditions.checkNotNull(buildRuleParams);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return super.appendDetailsToRuleKey(builder);
  }

  @Override
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(
      BuildRuleResolver ruleResolver,
      Iterable<BuildRule> declaredDeps,
      Iterable<BuildRule> inferredDeps) {
    // Create the AndroidBinaryGraphEnhancer for this rule.
    final ImmutableSortedSet<BuildRule> originalDepsAndApk =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(originalDeps)
            .add(apkUnderTestAsRule)
            .build();
    rulesToExcludeFromDex = FluentIterable.from(
        ImmutableSet.<JavaLibrary>builder()
            .addAll(apkUnderTest.getRulesToExcludeFromDex())
            .addAll(Classpaths.getClasspathEntries(apkUnderTest.getClasspathDeps()).keySet())
            .build())
        .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    AndroidTransitiveDependencyGraph androidTransitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(originalDepsAndApk);

    // The AndroidResourceDepsFinder is the primary data that differentiates how
    // AndroidInstrumentationApk and AndroidBinaryRule are built.
    androidResourceDepsFinder = new AndroidResourceDepsFinder(
        androidTransitiveDependencyGraph,
        rulesToExcludeFromDex) {
      @Override
      protected ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps() {
        // Filter out the AndroidResourceRules that are needed by this APK but not the APK under
        // test.
        ImmutableSet<HasAndroidResourceDeps> originalResources = ImmutableSet.copyOf(
            UberRDotJavaUtil.getAndroidResourceDeps(apkUnderTestAsRule));
        ImmutableList<HasAndroidResourceDeps> instrumentationResources =
            UberRDotJavaUtil.getAndroidResourceDeps(originalDepsAndApk);

        // Include all of the instrumentation resources first, in their original order.
        ImmutableList.Builder<HasAndroidResourceDeps> allResources = ImmutableList.builder();
        for (HasAndroidResourceDeps resource : instrumentationResources) {
          if (!originalResources.contains(resource)) {
            allResources.add(resource);
          }
        }
        return allResources.build();
      }
    };

    Path primaryDexPath = AndroidBinary.getPrimaryDexPath(getBuildTarget());
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        params,
        ruleResolver,
        ResourceCompressionMode.DISABLED,
        ResourceFilter.EMPTY_FILTER,
        androidResourceDepsFinder,
        manifest,
        PackageType.INSTRUMENTED,
        apkUnderTest.getCpuFilters(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        primaryDexPath,
        DexSplitMode.NO_SPLIT,
        /* rulesToExcludeFromDex */ ImmutableSortedSet.<BuildTarget>of(),
        JavacOptions.DEFAULTS,
        /* exopackage */ false,
        apkUnderTest.getKeystore());

    AndroidBinaryGraphEnhancer.EnhancementResult result =
        graphEnhancer.createAdditionalBuildables();

    setGraphEnhancementResult(result);

    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(result.getFinalDeps())
        .addAll(inferredDeps)
        .build();
  }
}
