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
import com.facebook.buck.android.UberRDotJavaBuildable.ResourceCompressionMode;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;


/**
 * Apk that functions as a test package in Android.
 * <p>
 * Android's
 * <a href="http://developer.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 * documentation includes a diagram that shows the relationship between an "application package" and
 * a "test package" when running a test. This corresponds to a test package. Note that a test
 * package has an interesting quirk where it is <em>compiled against</em> an application package,
 * but <em>must not include</em> the resources or Java classes of the application package.
 * Therefore, this class takes responsibility for making sure the appropriate bits are excluded.
 * Failing to do so will generate mysterious runtime errors when running the test.
 */
public class AndroidInstrumentationApk extends AndroidBinaryRule {

  private final AndroidBinaryRule apkUnderTest;
  private final ImmutableSortedSet<BuildRule> classpathDepsForInstrumentationApk;

  private AndroidInstrumentationApk(BuildRuleParams buildRuleParams,
      SourcePath manifest,
      AndroidBinaryRule apkUnderTest,
      ImmutableSet<BuildRule> buildRulesToExcludeFromDex,
      UberRDotJavaBuildable uberRDotJavaBuildable,
      AaptPackageResources aaptPackageResourcesBuildable,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      ImmutableSortedSet<BuildRule> classpathDepsForInstrumentationApk,
      AndroidTransitiveDependencyGraph androidTransitiveDependencyGraph) {
    super(buildRuleParams,
        manifest,
        apkUnderTest.getTarget(),
        classpathDepsForInstrumentationApk,
        apkUnderTest.getKeystore(),
        PackageType.INSTRUMENTED,
        buildRulesToExcludeFromDex,
        // Do not split the test apk even if the tested apk is split
        new DexSplitMode(
            /* shouldSplitDex */ false,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            /* useLinearAllocSplitDex */ false),
        apkUnderTest.isUseAndroidProguardConfigWithOptimizations(),
        apkUnderTest.getProguardConfig(),
        apkUnderTest.getResourceCompressionMode(),
        apkUnderTest.getPrimaryDexSubstrings(),
        apkUnderTest.getLinearAllocHardLimit(),
        apkUnderTest.getPrimaryDexClassesFile(),
        apkUnderTest.getCpuFilters(),

        // TODO(mbolin, t3338497): Figure out why AndroidInstrumentationApk.Builder cannot pass in
        // graphEnhancer.createDepsForPreDexing(ruleResolver) for this value.
        // For now, do not specify any pre-dex deps so that the traditional dexing
        // logic is used for an android_instrumentation_apk().
        /* preDexDeps */ ImmutableSet.<IntermediateDexRule>of(),

        uberRDotJavaBuildable,
        aaptPackageResourcesBuildable,
        apkUnderTest.getPreprocessJavaClassesDeps(),
        apkUnderTest.getPreprocessJavaClassesBash(),
        androidResourceDepsFinder,
        androidTransitiveDependencyGraph);
    this.apkUnderTest = apkUnderTest;
    this.classpathDepsForInstrumentationApk = Preconditions.checkNotNull(
        classpathDepsForInstrumentationApk);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_INSTRUMENTATION_APK;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("apkUnderTest", apkUnderTest)
        .setRuleNames("classpathDepsForInstrumentationApk", classpathDepsForInstrumentationApk);
  }

  public static Builder newAndroidInstrumentationApkRuleBuilder(
      AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<AndroidInstrumentationApk> {

    private SourcePath manifest = null;
    private BuildTarget apk = null;

    /** This should always be a subset of {@link #getDeps()}. */
    private ImmutableSet.Builder<BuildTarget> classpathDeps = ImmutableSet.builder();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public AndroidInstrumentationApk build(BuildRuleResolver ruleResolver) {
      BuildRule apkRule = ruleResolver.get(this.apk);
      if (apkRule == null) {
        throw new HumanReadableException("Must specify apk for " + getBuildTarget());
      } else if (!(apkRule instanceof InstallableBuildRule)) {
        throw new HumanReadableException(
            "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
            getBuildTarget(),
            apkRule.getFullyQualifiedName(),
            apkRule.getType().getName());
      }

      BuildRuleParams originalParams = createBuildRuleParams(ruleResolver);
      final ImmutableSortedSet<BuildRule> originalDeps = originalParams.getDeps();
      final AndroidBinaryRule apkUnderTest = getUnderlyingApk((InstallableBuildRule) apkRule);

      // Create the AndroidBinaryGraphEnhancer for this rule.
      ImmutableSet<BuildRule> buildRulesToExcludeFromDex = ImmutableSet.<BuildRule>builder()
          .addAll(apkUnderTest.getBuildRulesToExcludeFromDex())
          .addAll(Classpaths.getClasspathEntries(apkUnderTest.getClasspathDeps()).keySet())
          .build();
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex = FluentIterable
          .from(buildRulesToExcludeFromDex)
          .transform(new Function<BuildRule, BuildTarget>() {
            @Override
            public BuildTarget apply(BuildRule input) {
              return input.getBuildTarget();
            }
          })
          .toSet();
      AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
          originalParams, buildTargetsToExcludeFromDex);

      ImmutableSortedSet<BuildRule> classpathDepsForInstrumentationApk =
          getBuildTargetsAsBuildRules(ruleResolver, classpathDeps.build());
      AndroidTransitiveDependencyGraph androidTransitiveDependencyGraph =
          new AndroidTransitiveDependencyGraph(classpathDepsForInstrumentationApk);

      // The AndroidResourceDepsFinder is the primary data that differentiates how
      // AndroidInstrumentationApk and AndroidBinaryRule are built.
      AndroidResourceDepsFinder androidResourceDepsFinder = new AndroidResourceDepsFinder(
          androidTransitiveDependencyGraph,
          buildRulesToExcludeFromDex) {
        @Override
        protected ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps() {
          // Filter out the AndroidResourceRules that are needed by this APK but not the APK under test.
          ImmutableSet<HasAndroidResourceDeps> originalResources = ImmutableSet.copyOf(
              UberRDotJavaUtil.getAndroidResourceDeps(apkUnderTest));
          ImmutableList<HasAndroidResourceDeps> instrumentationResources =
              UberRDotJavaUtil.getAndroidResourceDeps(originalDeps);

          // Include all of the instrumentation resources first, in their original order.
          ImmutableList.Builder<HasAndroidResourceDeps> allResources = ImmutableList.builder();
          for (HasAndroidResourceDeps resource : instrumentationResources) {
            if (!originalResources.contains(resource)) {
              allResources.add(resource);
            }
          }
          return allResources.build();
        }

        @Override
        protected Set<HasAndroidResourceDeps> findMyAndroidResourceDepsUnsorted() {
          Collection<BuildRule> apk = Collections.<BuildRule>singleton(apkUnderTest);
          Set<HasAndroidResourceDeps> originalResources =
              UberRDotJavaUtil.getAndroidResourceDepsUnsorted(apk);
          Set<HasAndroidResourceDeps> instrumentationResources =
              UberRDotJavaUtil.getAndroidResourceDepsUnsorted(originalDeps);
          return Sets.difference(instrumentationResources, originalResources);
        }
      };

      AndroidBinaryGraphEnhancer.Result result = graphEnhancer.addBuildablesToCreateAaptResources(
          ruleResolver,
          /* resourceCompressionMode */ ResourceCompressionMode.DISABLED,
          /* resourceFilter */ ResourceFilter.EMPTY_FILTER,
          androidResourceDepsFinder,
          manifest,
          /* packageType */ PackageType.INSTRUMENTED,
          apkUnderTest.getCpuFilters(),
          /* preDexDeps */ ImmutableSet.<IntermediateDexRule>of());

      return new AndroidInstrumentationApk(result.getParams(),
          manifest,
          apkUnderTest,
          buildRulesToExcludeFromDex,
          result.getUberRDotJavaBuildable(),
          result.getAaptPackageResources(),
          androidResourceDepsFinder,
          getBuildTargetsAsBuildRules(ruleResolver, classpathDeps.build()),
          androidTransitiveDependencyGraph);
    }

    public Builder setManifest(SourcePath manifest) {
      this.manifest = manifest;
      return this;
    }

    public Builder setApk(BuildTarget apk) {
      this.apk = apk;
      return this;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder addClasspathDep(BuildTarget classpathDep) {
      this.classpathDeps.add(classpathDep);
      addDep(classpathDep);
      return this;
    }
  }

  private static AndroidBinaryRule getUnderlyingApk(InstallableBuildRule rule) {
    if (rule instanceof AndroidBinaryRule) {
      return (AndroidBinaryRule)rule;
    } else if (rule instanceof ApkGenrule) {
      return getUnderlyingApk(((ApkGenrule)rule).getInstallableBuildRule());
    } else {
      throw new IllegalStateException(
          rule.getFullyQualifiedName() +
          " must be backed by either an android_binary() or an apk_genrule()");
    }
  }

}
