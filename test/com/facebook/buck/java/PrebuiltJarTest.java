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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class PrebuiltJarTest {

  private static final Path PATH_TO_JUNIT_JAR = Paths.get("third-party/java/junit/junit-4.11.jar");

  private PrebuiltJar junitJarRule;

  @Before
  public void setUp() {
    BuildRuleParams buildRuleParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//lib", "junit").build()).build();

    junitJarRule = new PrebuiltJar(buildRuleParams,
        new PathSourcePath(PATH_TO_JUNIT_JAR),
        Optional.<SourcePath>of(new TestSourcePath("lib/junit-4.11-sources.jar")),
        /* gwtJar */ Optional.<SourcePath>absent(),
        Optional.of("http://junit-team.github.io/junit/javadoc/latest/"));
  }

  @Test
  public void testAbiKeyIsHashOfFileContents() throws IOException, InterruptedException {
    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = junitJarRule.getBuildSteps(buildContext, buildableContext);

    // Execute the CalculateAbiStep.
    Step calculateAbiStep = buildSteps.get(0);
    assertTrue(calculateAbiStep instanceof PrebuiltJar.CalculateAbiStep);
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = calculateAbiStep.execute(executionContext);
    assertEquals("Step should execute successfully.", 0, exitCode);
    buildableContext.assertContainsMetadataMapping(AbiRule.ABI_KEY_ON_DISK_METADATA,
        "4e031bb61df09069aeb2bffb4019e7a5034a4ee0");

    // Hydrate the rule as AbstractCachingBuildRule would.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo()
        .putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, "4e031bb61df09069aeb2bffb4019e7a5034a4ee0")
        .setFileContentsForPath(Paths.get("buck-out/gen/lib/junit.classes.txt"),
            ImmutableList.of(
                "com/example/Bar 1b1221d71c29aacb8e0b5b9eaffcd05e914ac55b",
                "com/example/Foo cea146e5aa5565a09e6a1ae9137044eb64b2cf45"));

    BuildResult buildResult = new BuildResult(BuildRuleSuccess.Type.BUILT_LOCALLY,
        CacheResult.MISS);
    CachingBuildEngine buildEngine = new CachingBuildEngine();
    buildEngine.createFutureFor(junitJarRule.getBuildTarget());
    buildEngine.doHydrationAfterBuildStepsFinish(
        // I am ashamed. Temporary hack, I hope.
        junitJarRule, buildResult, onDiskBuildInfo);

    // Make sure the ABI key is set as expected.
    HashCode hashForJar = Files.asByteSource(PATH_TO_JUNIT_JAR.toFile()).hash(Hashing.sha1());
    assertEquals("ABI key should be the sha1 of the file contents.",
        hashForJar.toString(),
        junitJarRule.getAbiKey().toString());

    assertEquals(
        "initializing from OnDiskBuildInfo should populate getClassNamesToHashes().",
        ImmutableSortedMap.of(
            "com/example/Bar", HashCode.fromString("1b1221d71c29aacb8e0b5b9eaffcd05e914ac55b"),
            "com/example/Foo", HashCode.fromString("cea146e5aa5565a09e6a1ae9137044eb64b2cf45")
        ),
        junitJarRule.getClassNamesToHashes());

    assertEquals(
        "Executing the step should record the ABI key as metadata.",
        ImmutableMap.<String, Object>of(
            AbiRule.ABI_KEY_ON_DISK_METADATA,
            hashForJar.toString()),
        buildableContext.getRecordedMetadata());
  }

  @Test
  public void testGetJavaSrcsIsEmpty() {
    assertTrue(junitJarRule.getJavaSrcs().isEmpty());
  }

  @Test
  public void testGetAnnotationProcessingDataIsEmpty() {
    assertTrue(junitJarRule.getAnnotationProcessingData().isEmpty());
  }
}
