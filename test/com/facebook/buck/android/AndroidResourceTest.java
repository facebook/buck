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

import static com.facebook.buck.android.AndroidResource.BuildOutput;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.FileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;


public class AndroidResourceTest {

  @Test
  public void testGetInputsToCompareToOutput() {
    // Create an android_resource rule with all sorts of input files that it depends on. If any of
    // these files is modified, then this rule should not be cached.
    BuildTarget buildTarget = new BuildTarget("//java/src/com/facebook/base", "res");
    AndroidResource androidResource = new AndroidResource(
        buildTarget,
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
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
        androidResource.getInputsToCompareToOutput());
  }

  @Test
  public void testRuleKeyForDifferentInputFilenames() throws IOException {
    BuildTarget buildTarget = new BuildTarget("//java/src/com/facebook/base", "res");
    AndroidResource androidResource1 = AndroidResourceRuleBuilder.newBuilder()
        .setBuildTarget(buildTarget)
        .setRes(Paths.get("java/src/com/facebook/base/res"))
        .setResSrcs(ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/res/drawable/A.xml")))
        .setRDotJavaPackage("com.facebook")
        .setAssets(Paths.get("java/src/com/facebook/base/assets"))
        .setAssetsSrcs(ImmutableSortedSet.of(
            Paths.get("java/src/com/facebook/base/assets/drawable/B.xml")))
        .setManifest(Paths.get("java/src/com/facebook/base/AndroidManifest.xml"))
        .buildAsBuildable();

    AndroidResource androidResource2 = AndroidResourceRuleBuilder.newBuilder()
        .setBuildTarget(buildTarget)
        .setRes(Paths.get("java/src/com/facebook/base/res"))
        .setResSrcs(ImmutableSortedSet.of(
                Paths.get("java/src/com/facebook/base/res/drawable/C.xml")))
        .setRDotJavaPackage("com.facebook")
        .setAssets(Paths.get("java/src/com/facebook/base/assets"))
        .setAssetsSrcs(ImmutableSortedSet.of(
                Paths.get("java/src/com/facebook/base/assets/drawable/B.xml")))
        .setManifest(Paths.get("java/src/com/facebook/base/AndroidManifest.xml"))
        .buildAsBuildable();

    String commonHash = Strings.repeat("a", 40);
    FakeFileHashCache fakeFileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
        "java/src/com/facebook/base/res/drawable/A.xml", commonHash,
        "java/src/com/facebook/base/assets/drawable/B.xml", Strings.repeat("b", 40),
        "java/src/com/facebook/base/res/drawable/C.xml", commonHash,
        "java/src/com/facebook/base/AndroidManifest.xml", Strings.repeat("d", 40)));

    RuleKey ruleKey1 =
        getRuleKeyWithoutDepsFromResource(buildTarget, androidResource1, fakeFileHashCache);
    RuleKey ruleKey2 =
        getRuleKeyWithoutDepsFromResource(buildTarget, androidResource2, fakeFileHashCache);

    assertNotEquals("The two android_resource rules should have different rule keys.",
        ruleKey1,
        ruleKey2);
  }

  private RuleKey getRuleKeyWithoutDepsFromResource(
      BuildTarget buildTarget,
      AndroidResource resource,
      FileHashCache fileHashCache) throws IOException {
    BuildRule rule = new AbstractBuildable.AnonymousBuildRule(
        AndroidResourceDescription.TYPE,
        resource,
        new FakeBuildRuleParams(buildTarget,
            ImmutableSortedSet.<BuildRule>of(),
            ImmutableSet.of(BuildTargetPattern.MATCH_ALL),
            fileHashCache));
    return rule.getRuleKeyWithoutDeps();
  }
  /**
   * Create the following dependency graph of {@link AndroidResource}s:
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
    BuildRule c = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
            .setRes(Paths.get("res_c"))
            .setRDotJavaPackage("com.facebook")
            .build());

    BuildRule b = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
            .setRes(Paths.get("res_b"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    BuildRule d = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
            .setRes(Paths.get("res_d"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    BuildRule a = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
            .setRes(Paths.get("res_a"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(b, c, d))
            .build());

    ImmutableList<HasAndroidResourceDeps> deps = UberRDotJavaUtil.getAndroidResourceDeps(a);

    Function<BuildRule, Buildable> ruleToBuildable = new Function<BuildRule, Buildable>() {
      @Override
      public Buildable apply(BuildRule input) {
        return input.getBuildable();
      }
    };
    // Note that a topological sort for a DAG is not guaranteed to be unique. In this particular
    // case, there are two possible valid outcomes.
    ImmutableList<Buildable> validResult1 = FluentIterable.from(ImmutableList.of(a, b, d, c))
        .transform(ruleToBuildable)
        .toList();
    ImmutableList<Buildable> validResult2 = FluentIterable.from(ImmutableList.of(a, d, b, c))
        .transform(ruleToBuildable)
        .toList();

    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
        deps.equals(validResult1) || deps.equals(validResult2));

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver)
        .getBuildable();

    BuildRule e = AndroidBinaryBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:e"))
            .setManifest(new TestSourcePath("AndroidManfiest.xml"))
            .setTarget("Google Inc.:Google APIs:16")
            .setKeystore(keystore)
            .setOriginalDeps(ImmutableSortedSet.of(a, c))
            .build(ruleResolver);
    e.getBuildable().getEnhancedDeps(ruleResolver);

    ImmutableList<HasAndroidResourceDeps> deps2 = UberRDotJavaUtil.getAndroidResourceDeps(a);
    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
            deps2.equals(validResult1) || deps2.equals(validResult2));
  }

  @Test
  public void testAbiKeyIsAbiKeyForDepsWhenResourcesAreAbsent() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1"))
            .build()
    );
    setAndroidResourceBuildOutput(resourceRule1, "a");
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2"))
            .build()
    );
    setAndroidResourceBuildOutput(resourceRule2, "b");
    BuildRule resourceRule3 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res3"))
            .setDeps(ImmutableSortedSet.of(resourceRule1, resourceRule2))
            .build());

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    assertTrue(
        resourceRule3
            .getBuildable()
            .getBuildSteps(
                EasyMock.createMock(BuildContext.class),
                buildableContext)
            .isEmpty());

    Sha1HashCode expectedSha1 = HasAndroidResourceDeps.ABI_HASHER.apply(
        ImmutableList.of(
            (HasAndroidResourceDeps) resourceRule1.getBuildable(),
            (HasAndroidResourceDeps) resourceRule2.getBuildable()));
    buildableContext.assertContainsMetadataMapping(
        AndroidResource.METADATA_KEY_FOR_ABI,
        expectedSha1.getHash());
  }

  private void setAndroidResourceBuildOutput(BuildRule resourceRule, String hashChar) {
    if (resourceRule.getBuildable() instanceof AndroidResource) {
      ((AndroidResource) resourceRule.getBuildable())
          .getBuildOutputInitializer()
          .setBuildOutput(new BuildOutput(new Sha1HashCode(Strings.repeat(hashChar, 40))));
    }
  }
}
