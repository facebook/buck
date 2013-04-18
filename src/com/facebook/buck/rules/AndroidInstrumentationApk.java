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

package com.facebook.buck.rules;

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Map;

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

  private AndroidInstrumentationApk(BuildRuleParams buildRuleParams,
      String manifest,
      AndroidBinaryRule apkUnderTest) {
    super(buildRuleParams,
        manifest,
        apkUnderTest.getTarget(),
        apkUnderTest.getKeystorePropertiesPath(),
        PackageType.INSTRUMENTED,
        // Do not include the classes that will already be in the classes.dex of the APK under test.
        ImmutableSet.<String>builder()
            .addAll(apkUnderTest.getClasspathEntriesToExcludeFromDex())
            .addAll(apkUnderTest.getClasspathEntriesForDeps())
            .build(),
        // Do not split the test apk even if the tested apk is split
        new DexSplitMode(false, ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE),
        apkUnderTest.isUseAndroidProguardConfigWithOptimizations(),
        apkUnderTest.getProguardConfig(),
        apkUnderTest.isCompressResources(),
        apkUnderTest.getPrimaryDexSubstrings(),
        apkUnderTest.getResourceFilter());
    this.apkUnderTest = apkUnderTest;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_INSTRUMENTATION_APK;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("apkUnderTest", apkUnderTest);
  }

  @Override
  protected ImmutableList<AndroidResourceRule> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    // Filter out the AndroidResourceRules that are needed by this APK but not the APK under test.
    ImmutableSet<AndroidResourceRule> originalResources = ImmutableSet.copyOf(
        AndroidResourceRule.getAndroidResourceDeps(apkUnderTest, graph));
    ImmutableList<AndroidResourceRule> instrumentationResources =
        AndroidResourceRule.getAndroidResourceDeps(this, graph);

    // Include all of the instrumentation resources first, in their original order.
    ImmutableList.Builder<AndroidResourceRule> allResources = ImmutableList.builder();
    for (AndroidResourceRule resource : instrumentationResources) {
      if (!originalResources.contains(resource)) {
        allResources.add(resource);
      }
    }
    return allResources.build();
  }

  public static Builder newAndroidInstrumentationApkRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    private String manifest = null;
    private String apk = null;

    @Override
    public BuildRule build(Map<String, BuildRule> buildRuleIndex) {
      BuildRule apkRule = buildRuleIndex.get(this.apk);
      if (apkRule == null) {
        throw new HumanReadableException("Must specify apk for " + getBuildTarget());
      } else if (!(apkRule instanceof InstallableBuildRule)) {
        throw new HumanReadableException(
            "In %s, apk='%s' must be an android_binary() or apk_genrule() but was %s().",
            getBuildTarget(),
            apkRule.getFullyQualifiedName(),
            apkRule.getType().getDisplayName());
      }

      AndroidBinaryRule underlyingApk = getUnderlyingApk((InstallableBuildRule)apkRule);

      return new AndroidInstrumentationApk(createBuildRuleParams(buildRuleIndex),
          manifest,
          underlyingApk);
    }

    public Builder setManifest(String manifest) {
      this.manifest = manifest;
      return this;
    }

    public Builder setApk(String apk) {
      this.apk = apk;
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
