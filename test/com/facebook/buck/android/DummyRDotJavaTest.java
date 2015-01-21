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

import static com.facebook.buck.android.AndroidResource.BuildOutput;
import static com.facebook.buck.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class DummyRDotJavaTest {

  private static final String RESOURCE_RULE1_KEY = Strings.repeat("a", 40);
  private static final String RESOURCE_RULE2_KEY = Strings.repeat("b", 40);

  @Test
  public void testBuildSteps() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1"))
            .build());
    setAndroidResourceBuildOutput(resourceRule1, RESOURCE_RULE1_KEY);
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2"))
            .build());
    setAndroidResourceBuildOutput(resourceRule2, RESOURCE_RULE2_KEY);

    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//java/base:rule")).build(),
        pathResolver,
        ImmutableSet.of(
            (HasAndroidResourceDeps) resourceRule1,
            (HasAndroidResourceDeps) resourceRule2),
        ANDROID_JAVAC_OPTIONS);

    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> steps = dummyRDotJava.getBuildSteps(EasyMock.createMock(BuildContext.class),
        buildableContext);
    assertEquals("DummyRDotJava returns an incorrect number of Steps.", 6, steps.size());

    String rDotJavaSrcFolder = "buck-out/bin/java/base/__rule_rdotjava_src__";
    String rDotJavaBinFolder = "buck-out/bin/java/base/__rule_rdotjava_bin__";
    String rDotJavaAbiFolder = "buck-out/gen/java/base/__rule_dummyrdotjava_abi__";

    List<String> expectedStepDescriptions = Lists.newArrayList(
        makeCleanDirDescription(rDotJavaSrcFolder),
        mergeAndroidResourcesDescription(
            ImmutableList.of(
                (AndroidResource) resourceRule1,
                (AndroidResource) resourceRule2)),
        makeCleanDirDescription(rDotJavaBinFolder),
        makeCleanDirDescription(rDotJavaAbiFolder),
        javacInMemoryDescription(rDotJavaBinFolder),
        "calculate_abi buck-out/bin/java/base/__rule_rdotjava_bin__");

    MoreAsserts.assertSteps(
        "DummyRDotJava.getBuildSteps() must return these exact steps.",
        expectedStepDescriptions,
        steps,
        TestExecutionContext.newInstance());

    assertEquals(ImmutableSet.of(Paths.get(rDotJavaBinFolder)),
        buildableContext.getRecordedArtifactDirectories());

    Sha1HashCode expectedSha1 = AndroidResource.ABI_HASHER.apply(
        ImmutableList.of(
            (HasAndroidResourceDeps) resourceRule1,
            (HasAndroidResourceDeps) resourceRule2));
    assertEquals(expectedSha1, dummyRDotJava.getAbiKeyForDeps());
  }

  @Test
  public void testRDotJavaBinFolder() {
    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//java/com/example:library"))
            .build(),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<HasAndroidResourceDeps>of(),
        ANDROID_JAVAC_OPTIONS);
    assertEquals(Paths.get("buck-out/bin/java/com/example/__library_rdotjava_bin__"),
        dummyRDotJava.getRDotJavaBinFolder());
  }

  @Test
  public void testInitializeFromDisk() {
    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//java/base:rule")).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSet.<HasAndroidResourceDeps>of(),
        ANDROID_JAVAC_OPTIONS);

    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    String keyHash = Strings.repeat("a", 40);
    onDiskBuildInfo.putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, keyHash);

    assertEquals(
        keyHash,
        dummyRDotJava.initializeFromDisk(onDiskBuildInfo).rDotTxtSha1.getHash());
  }

  private static String makeCleanDirDescription(String dirname) {
    return String.format("rm -r -f %s && mkdir -p %s", dirname, dirname);
  }

  private static String javacInMemoryDescription(String rDotJavaClassesFolder) {
    Set<Path> javaSourceFiles = ImmutableSet.of(
        Paths.get("buck-out/bin/java/base/__rule_rdotjava_src__/com/facebook/R.java"));
    return RDotJava.createJavacStepForDummyRDotJavaFiles(
        javaSourceFiles,
        Paths.get(rDotJavaClassesFolder),
        ANDROID_JAVAC_OPTIONS,
        /* buildTarget */ null)
        .getDescription(TestExecutionContext.newInstance());
  }

  private static String mergeAndroidResourcesDescription(List<AndroidResource> resourceRules) {
    List<String> sortedSymbolsFiles = FluentIterable.from(resourceRules)
        .transform(Functions.toStringFunction())
        .toList();
    return "android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles);
  }

  private void setAndroidResourceBuildOutput(BuildRule resourceRule, String sha1HashCode) {
    if (resourceRule instanceof AndroidResource) {
      ((AndroidResource) resourceRule)
          .getBuildOutputInitializer()
          .setBuildOutput(new BuildOutput(ImmutableSha1HashCode.of(sha1HashCode)));
    }
  }
}
