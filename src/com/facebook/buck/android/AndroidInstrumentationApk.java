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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

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

  private final SourcePath manifest;
  private final AndroidBinary apkUnderTest;
  private final BuildRuleParams params;

  AndroidInstrumentationApk(
      BuildRuleParams buildRuleParams,
      SourcePath manifest,
      AndroidBinary apkUnderTest,
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
        apkUnderTest.getSdkProguardConfig(),
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
    rulesToExcludeFromDex = FluentIterable.from(
        ImmutableSet.<JavaLibrary>builder()
            .addAll(apkUnderTest.getRulesToExcludeFromDex())
            .addAll(Classpaths.getClasspathEntries(apkUnderTest.getClasspathDeps()).keySet())
            .build())
        .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);

    // TODO(natthu): Instrumentation APKs should also exclude native libraries and assets from the
    // apk under test.
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        apkUnderTest.getAndroidPackageableCollection().resourceDetails;
    ImmutableSet<BuildTarget> resourcesToExclude = ImmutableSet.copyOf(
        Iterables.concat(
            resourceDetails.resourcesWithNonEmptyResDir,
            resourceDetails.resourcesWithEmptyResButNonEmptyAssetsDir));

    Path primaryDexPath = AndroidBinary.getPrimaryDexPath(getBuildTarget());
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        params,
        ruleResolver,
        ResourceCompressionMode.DISABLED,
        ResourceFilter.EMPTY_FILTER,
        manifest,
        PackageType.INSTRUMENTED,
        apkUnderTest.getCpuFilters(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        primaryDexPath,
        DexSplitMode.NO_SPLIT,
        FluentIterable.from(rulesToExcludeFromDex).transform(TO_TARGET).toSet(),
        resourcesToExclude,
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
