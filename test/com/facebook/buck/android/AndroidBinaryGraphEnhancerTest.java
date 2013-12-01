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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

public class AndroidBinaryGraphEnhancerTest {

  @Test
  public void testCreateDepsForPreDexing() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    Function<String, Path> pathRelativizer = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return Paths.get(input);
      }
    };
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = new BuildTarget("//java/com/example", "dep1");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(
            new DefaultBuildRuleBuilderParams(pathRelativizer, ruleKeyBuilderFactory))
            .setBuildTarget(javaDep1BuildTarget)
            .addSrc("java/com/example/Dep1.java"));

    BuildTarget javaDep2BuildTarget = new BuildTarget("//java/com/example", "dep2");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(
            new DefaultBuildRuleBuilderParams(pathRelativizer, ruleKeyBuilderFactory))
            .setBuildTarget(javaDep2BuildTarget)
            .addSrc("java/com/example/Dep2.java"));

    BuildTarget javaLibBuildTarget = new BuildTarget("//java/com/example", "lib");
    DefaultJavaLibraryRule javaLib = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(
            new DefaultBuildRuleBuilderParams(pathRelativizer, ruleKeyBuilderFactory))
            .setBuildTarget(javaLibBuildTarget)
            .addSrc("java/com/example/Lib.java")
            .addDep(javaDep1BuildTarget)
            .addDep(javaDep2BuildTarget));

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.<BuildRule>of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildRuleParams originalParams = new BuildRuleParams(
        new BuildTarget("//java/com/example", "apk"),
        originalDeps,
        /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
        pathRelativizer,
        ruleKeyBuilderFactory);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams, buildRulesToExcludeFromDex);
    ImmutableSet<IntermediateDexRule> depsForPreDexing = graphEnhancer.createDepsForPreDexing(
        ruleResolver);
    assertEquals(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx " +
            "list.",
        2,
    		depsForPreDexing.size());

    Iterator<IntermediateDexRule> depsForPreDexingIter = depsForPreDexing.iterator();
    BuildRule preDexRule1 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:dep1#dex", preDexRule1.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule1.getBuildTarget()));

    BuildRule preDexRule2 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:lib#dex", preDexRule2.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule2.getBuildTarget()));
  }
}
