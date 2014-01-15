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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;


public class AndroidResourceRuleTest {

  @Test
  public void testGetInputsToCompareToOutput() {
    // Create an android_resource rule with all sorts of input files that it depends on. If any of
    // these files is modified, then this rule should not be cached.
    BuildTarget buildTarget = new BuildTarget("//java/src/com/facebook/base", "res");
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        buildTarget,
        ImmutableSortedSet.<BuildRule>of());
    AndroidResourceRule androidResourceRule = new AndroidResourceRule(
        buildRuleParams,
        Paths.get("java/src/com/facebook/base/res"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/res/drawable/E.xml"),
            Paths.get("java/src/com/facebook/base/res/drawable/A.xml"),
            Paths.get("java/src/com/facebook/base/res/drawable/C.xml")),
        "com.facebook",
        Paths.get("java/src/com/facebook/base/assets"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/assets/drawable/F.xml"),
            Paths.get("java/src/com/facebook/base/assets/drawable/B.xml"),
            Paths.get("java/src/com/facebook/base/assets/drawable/D.xml")),
        Paths.get("java/src/com/facebook/base/AndroidManifest.xml"),
        /* hasWhitelisted */ false);

    // Test getInputsToCompareToOutput().
    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should return an alphabetically sorted list of all input " +
        "files that contribute to this android_resource() rule.",
        ImmutableList.of(
            Paths.get("java/src/com/facebook/base/AndroidManifest.xml"),
            Paths.get("java/src/com/facebook/base/assets/drawable/B.xml"),
            Paths.get("java/src/com/facebook/base/assets/drawable/D.xml"),
            Paths.get("java/src/com/facebook/base/assets/drawable/F.xml"),
            Paths.get("java/src/com/facebook/base/res/drawable/A.xml"),
            Paths.get("java/src/com/facebook/base/res/drawable/C.xml"),
            Paths.get("java/src/com/facebook/base/res/drawable/E.xml")),
        androidResourceRule.getInputsToCompareToOutput());
  }

  @Test
  public void testRuleKeyForDifferentInputFilenames() throws IOException {
    BuildTarget buildTarget = new BuildTarget("//java/src/com/facebook/base", "res");
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        buildTarget,
        ImmutableSortedSet.<BuildRule>of());
    AndroidResourceRule androidResourceRule1 = new AndroidResourceRule(
        buildRuleParams,
        Paths.get("java/src/com/facebook/base/res"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/res/drawable/A.xml")),
        "com.facebook",
        Paths.get("java/src/com/facebook/base/assets"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/assets/drawable/B.xml")),
        Paths.get("java/src/com/facebook/base/AndroidManifest.xml"),
        /* hasWhitelisted */ false);
    AndroidResourceRule androidResourceRule2 = new AndroidResourceRule(
        buildRuleParams,
        Paths.get("java/src/com/facebook/base/res"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/res/drawable/C.xml")),
        "com.facebook",
        Paths.get("java/src/com/facebook/base/assets"),
        ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/assets/drawable/B.xml")),
        Paths.get("java/src/com/facebook/base/AndroidManifest.xml"),
        /* hasWhitelisted */ false);

    String commonHash = Strings.repeat("a", 40);
    FakeFileHashCache fakeFileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
        "java/src/com/facebook/base/res/drawable/A.xml", commonHash,
        "java/src/com/facebook/base/assets/drawable/B.xml", Strings.repeat("b", 40),
        "java/src/com/facebook/base/res/drawable/C.xml", commonHash,
        "java/src/com/facebook/base/AndroidManifest.xml", Strings.repeat("d", 40)));

    RuleKey.Builder.RuleKeyPair pair1 = androidResourceRule1.appendToRuleKey(
        RuleKey.builder(androidResourceRule1, fakeFileHashCache))
        .build();
    RuleKey.Builder.RuleKeyPair pair2 = androidResourceRule2.appendToRuleKey(
        RuleKey.builder(androidResourceRule2, fakeFileHashCache))
        .build();

    assertNotEquals("The two android_resource rules should have different rule keys.",
        pair1.getRuleKeyWithoutDeps(),
        pair2.getRuleKeyWithoutDeps());
  }

  /**
   * Create the following dependency graph of {@link AndroidResourceRule}s:
   * <pre>
   *    A
   *  / | \
   * B  |  D
   *  \ | /
   *    C
   * </pre>
   * Note that an ordinary breadth-first traversal would yield either {@code A B C D} or
   * {@code A D C B}. However, either of these would be <em>wrong</em> in this case because we need
   * to be sure that we perform a topological sort, the resulting traversal of which is either
   * {@code A B D C} or {@code A D B C}.
   * <p>
   * We choose these letters in particular.
   */
  @Test
  public void testGetAndroidResourceDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidResourceRule c = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
            .setRes(Paths.get("res_c"))
            .setRDotJavaPackage("com.facebook"));

    AndroidResourceRule b = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
        .setRes(Paths.get("res_b"))
        .setRDotJavaPackage("com.facebook")
        .addDep(BuildTargetFactory.newInstance("//:c")));

    AndroidResourceRule d = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
            .setRes(Paths.get("res_d"))
            .setRDotJavaPackage("com.facebook")
            .addDep(BuildTargetFactory.newInstance("//:c")));

    AndroidResourceRule a = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
        .setRes(Paths.get("res_a"))
        .setRDotJavaPackage("com.facebook")
        .addDep(BuildTargetFactory.newInstance("//:b"))
        .addDep(BuildTargetFactory.newInstance("//:c"))
        .addDep(BuildTargetFactory.newInstance("//:d")));

    ImmutableList<HasAndroidResourceDeps> deps = UberRDotJavaUtil.getAndroidResourceDeps(a);

    // Note that a topological sort for a DAG is not guaranteed to be unique. In this particular
    // case, there are two possible valid outcomes.
    ImmutableList<AndroidResourceRule> validResult1 = ImmutableList.of(a, b, d, c);
    ImmutableList<AndroidResourceRule> validResult2 = ImmutableList.of(a, d, b, c);

    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
        deps.equals(validResult1) || deps.equals(validResult2));

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    ruleResolver.buildAndAddToIndex(
        Keystore.newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));
    AndroidBinaryRule e = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:e"))
        .setManifest(new FileSourcePath("AndroidManfiest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystoreTarget)
        .addDep(BuildTargetFactory.newInstance("//:a"))
        .addDep(BuildTargetFactory.newInstance("//:c")));

    ImmutableList<HasAndroidResourceDeps> deps2 = UberRDotJavaUtil.getAndroidResourceDeps(e);
    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
            deps2.equals(validResult1) || deps2.equals(validResult2));
  }
}
