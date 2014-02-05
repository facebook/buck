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

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
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
    AndroidResourceRule resourceRule1 = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1")));
    resourceRule1.setBuildOutput(
        new AndroidResourceRule.BuildOutput(new Sha1HashCode(RESOURCE_RULE1_KEY)));
    AndroidResourceRule resourceRule2 = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2")));
    resourceRule2.setBuildOutput(
        new AndroidResourceRule.BuildOutput(new Sha1HashCode(RESOURCE_RULE2_KEY)));

    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        ImmutableList.<HasAndroidResourceDeps>of(resourceRule1, resourceRule2),
        BuildTargetFactory.newInstance("//java/base:rule"));

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
            ImmutableList.of(resourceRule1, resourceRule2), rDotJavaSrcFolder),
        makeCleanDirDescription(rDotJavaBinFolder),
        makeCleanDirDescription(rDotJavaAbiFolder),
        javacInMemoryDescription(rDotJavaBinFolder, rDotJavaAbiFolder + "/abi"),
        "record_abi_key");

    MoreAsserts.assertSteps(
        "DummyRDotJava.getBuildSteps() must return these exact steps.",
        expectedStepDescriptions,
        steps,
        TestExecutionContext.newInstance());

    assertEquals(ImmutableSet.of(Paths.get(rDotJavaBinFolder)),
        buildableContext.getRecordedArtifactDirectories());

    Sha1HashCode expectedSha1 = AndroidResourceRule.HASHER.apply(
        ImmutableList.<HasAndroidResourceDeps>of(resourceRule1, resourceRule2));
    assertEquals(expectedSha1, dummyRDotJava.getAbiKeyForDeps());
  }

  @Test
  public void testRDotJavaBinFolder() {
    DummyRDotJava dummyRDotJava = new DummyRDotJava(ImmutableList.<HasAndroidResourceDeps>of(),
        BuildTargetFactory.newInstance("//java/com/example:library"));
    assertEquals(Paths.get("buck-out/bin/java/com/example/__library_rdotjava_bin__"),
        dummyRDotJava.getRDotJavaBinFolder());
  }

  @Test
  public void testInitializeFromDisk() {
    DummyRDotJava dummyRDotJava = new DummyRDotJava(
        ImmutableList.<HasAndroidResourceDeps>of(),
        BuildTargetFactory.newInstance("//java/base:rule"));

    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    String keyHash = Strings.repeat("a", 40);
    onDiskBuildInfo.putMetadata(DummyRDotJava.METADATA_KEY_FOR_ABI_KEY, keyHash);

    assertEquals(
        keyHash,
        dummyRDotJava.initializeFromDisk(onDiskBuildInfo).rDotTxtSha1.getHash());
  }

  private static String makeCleanDirDescription(String dirname) {
    return String.format("rm -r -f %s && mkdir -p %s", dirname, dirname);
  }

  private static String javacInMemoryDescription(String rDotJavaClassesFolder,
                                                 String pathToAbiOutputFile) {
    Set<Path> javaSourceFiles = ImmutableSet.of(
        Paths.get("buck-out/bin/java/base/__rule_rdotjava_src__/com.facebook/R.java"));
    return UberRDotJavaUtil.createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFiles,
        Paths.get(rDotJavaClassesFolder),
        Optional.of(Paths.get(pathToAbiOutputFile)))
        .getDescription(TestExecutionContext.newInstance());
  }

  private static String mergeAndroidResourcesDescription(
      List<AndroidResourceRule> resourceRules,
      String rDotJavaSourceFolder) {
    List<String> sortedSymbolsFiles = FluentIterable.from(resourceRules)
        .transform(new Function<AndroidResourceRule, Path>() {
          @Override
          public Path apply(AndroidResourceRule input) {
            return input.getPathToTextSymbolsFile();
          }
        })
        .transform(Functions.toStringFunction())
        .toList();
    return "android-res-merge " + Joiner.on(' ').join(sortedSymbolsFiles) +
        " -o " + rDotJavaSourceFolder;
  }
}
