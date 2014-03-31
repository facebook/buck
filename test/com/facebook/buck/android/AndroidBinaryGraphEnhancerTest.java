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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
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
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(
            new FakeBuildRuleBuilderParams(ruleKeyBuilderFactory))
            .setBuildTarget(javaDep1BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep1.java")));

    BuildTarget javaDep2BuildTarget = new BuildTarget("//java/com/example", "dep2");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(
            new FakeBuildRuleBuilderParams(ruleKeyBuilderFactory))
            .setBuildTarget(javaDep2BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep2.java")));

    BuildTarget javaLibBuildTarget = new BuildTarget("//java/com/example", "lib");
    DefaultJavaLibrary javaLib = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(
            new FakeBuildRuleBuilderParams(ruleKeyBuilderFactory))
            .setBuildTarget(javaLibBuildTarget)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1BuildTarget)
            .addDep(javaDep2BuildTarget));

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.<BuildRule>of(javaLib);
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
        createStrictMock(FileSourcePath.class),
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
}
