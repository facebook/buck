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

import static com.facebook.buck.android.AndroidBinaryGraphEnhancer.EnhancementResult;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Iterator;

public class AndroidBinaryGraphEnhancerTest {

  @Test
  public void testCreateDepsForPreDexing() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = new BuildTarget("//java/com/example", "dep1");
    BuildRule javaDep1 = JavaLibraryBuilder
        .createBuilder(javaDep1BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep1.java"))
        .build(ruleResolver);

    BuildTarget javaDep2BuildTarget = new BuildTarget("//java/com/example", "dep2");
    BuildRule javaDep2 = JavaLibraryBuilder
        .createBuilder(javaDep2BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep2.java"))
        .build(ruleResolver);

    BuildTarget javaLibBuildTarget = new BuildTarget("//java/com/example", "lib");
    BuildRule javaLib = JavaLibraryBuilder
        .createBuilder(javaLibBuildTarget)
        .addSrc(Paths.get("java/com/example/Lib.java"))
        .addDep(javaDep1)
        .addDep(javaDep2)
        .build(ruleResolver);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = new BuildTarget("//java/com/example", "apk");
    BuildRuleParams originalParams = new BuildRuleParams(
        apkTarget,
        originalDeps,
        /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
        new FakeProjectFilesystem(),
        ruleKeyBuilderFactory);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.DISABLED,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        createStrictMock(AndroidResourceDepsFinder.class),
        createStrictMock(PathSourcePath.class),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.< AndroidBinary.TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ true,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        buildRulesToExcludeFromDex,
        JavacOptions.DEFAULTS,
        /* exopackage */ false,
        createStrictMock(Keystore.class));

    UberRDotJava uberRDotJava = createMock(UberRDotJava.class);
    BuildTarget uberRDotJavaTarget =
        new BuildTarget("//java/com/example", "apk", "uber_r_dot_java");
    expect(uberRDotJava.getBuildTarget()).andStubReturn(uberRDotJavaTarget);
    replay(uberRDotJava);
    BuildRule uberRDotJavaRule = new AbstractBuildable.AnonymousBuildRule(
        BuildRuleType.UBER_R_DOT_JAVA,
        uberRDotJava,
        new BuildRuleParams(
            uberRDotJavaTarget,
            ImmutableSortedSet.<BuildRule>of(),
            ImmutableSet.of(BuildTargetPattern.MATCH_ALL),
            new FakeProjectFilesystem(),
            ruleKeyBuilderFactory));
    ruleResolver.addToIndex(uberRDotJavaTarget, uberRDotJavaRule);

    BuildRule preDexMergeRule =
        graphEnhancer.createPreDexMergeRule(uberRDotJava);
    BuildTarget dexMergeTarget = new BuildTarget("//java/com/example", "apk", "dex_merge");
    BuildRule dexMergeRule = ruleResolver.get(dexMergeTarget);

    assertEquals(dexMergeRule.getBuildable(), preDexMergeRule.getBuildable());

    assertEquals(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx " +
            "list.  And we should depend on uber_r_dot_java.",
        3,
        dexMergeRule.getDeps().size());

    Iterator<BuildRule> depsForPreDexingIter = dexMergeRule.getDeps().iterator();

    BuildRule shouldBeUberRDotJavaRule = depsForPreDexingIter.next();
    assertEquals(uberRDotJavaRule, shouldBeUberRDotJavaRule);

    BuildRule preDexRule1 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:dep1#dex", preDexRule1.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule1.getBuildTarget()));

    BuildRule preDexRule2 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:lib#dex", preDexRule2.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule2.getBuildTarget()));
  }

  @Test
  public void testAllBuildablesExceptPreDexRule() {
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    BuildRuleParams originalParams = new BuildRuleParams(
        apkTarget,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSortedSet.<BuildTargetPattern>of(),
        new FakeProjectFilesystem(),
        new FakeRuleKeyBuilderFactory());
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    AndroidResourceDepsFinder depsFinder = createStrictMock(AndroidResourceDepsFinder.class);
    expect(depsFinder.getAndroidResources()).andStubReturn(
        ImmutableList.<HasAndroidResourceDeps>of());
    expect(depsFinder.getAssetOnlyAndroidResources()).andStubReturn(
        ImmutableList.<HasAndroidResourceDeps>of());
    expect(depsFinder.getAndroidTransitiveDependencies()).andStubReturn(
        AndroidTransitiveDependencies.EMPTY);

    // set it up.
    Keystore keystore = createStrictMock(Keystore.class);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        depsFinder,
        new TestSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<AndroidBinary.TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        ImmutableSet.<BuildTarget>of(),
        JavacOptions.DEFAULTS,
        /* exopackage */ true,
        keystore);
    replay(depsFinder, keystore);
    EnhancementResult result = graphEnhancer.createAdditionalBuildables();

    ImmutableSortedSet<BuildRule> finalDeps = result.getFinalDeps();
    // Verify that the only dep is computeExopackageDepsAbi
    assertEquals(1, finalDeps.size());
    BuildRule computeExopackageDepsAbiRule =
        findRuleForBuilable(ruleResolver, ComputeExopackageDepsAbi.class);
    assertEquals(computeExopackageDepsAbiRule, finalDeps.first());

    FilteredResourcesProvider resourcesProvider = result.getFilteredResourcesProvider();
    assertTrue(resourcesProvider instanceof ResourcesFilter);
    BuildRule resourcesFilterRule = findRuleForBuilable(ruleResolver, ResourcesFilter.class);

    BuildRule uberRDotJavaRule = findRuleForBuilable(ruleResolver, UberRDotJava.class);
    MoreAsserts.assertDepends(
        "UberRDotJava must depend on ResourcesFilter",
        uberRDotJavaRule,
        resourcesFilterRule);

    BuildRule packageStringAssetsRule =
        findRuleForBuilable(ruleResolver, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on ResourcesFilter",
        packageStringAssetsRule,
        uberRDotJavaRule);

    BuildRule aaptPackageResourcesRule =
        findRuleForBuilable(ruleResolver, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on ResourcesFilter",
        aaptPackageResourcesRule,
        resourcesFilterRule);

    assertFalse(result.getPreDexMerge().isPresent());

    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on ResourcesFilter",
        computeExopackageDepsAbiRule,
        resourcesFilterRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on UberRDotJava",
        computeExopackageDepsAbiRule,
        uberRDotJavaRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on PackageStringAssets",
        computeExopackageDepsAbiRule,
        packageStringAssetsRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on AaptPackageResources",
        computeExopackageDepsAbiRule,
        aaptPackageResourcesRule);

    assertTrue(result.getPackageStringAssets().isPresent());
    assertTrue(result.getComputeExopackageDepsAbi().isPresent());

    verify(depsFinder, keystore);
  }

  private BuildRule findRuleForBuilable(BuildRuleResolver ruleResolver, Class<?> buildableClass) {
    for (BuildRule rule : ruleResolver.getBuildRules()) {
      if (buildableClass.isAssignableFrom(rule.getBuildable().getClass())) {
        return rule;
      }
    }
    fail("Could not find builable of type " + buildableClass.getCanonicalName());
    return null;
  }
}
