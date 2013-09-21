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
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class PrebuiltJarRuleTest {

  private static final String PATH_TO_JUNIT_JAR = "lib/junit-4.11.jar";

  private PrebuiltJarRule junitJarRule;

  @Before
  public void setUp() {
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        new BuildTarget("//lib", "junit"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibilityPatterns */ ImmutableSet.of(BuildTargetPattern.MATCH_ALL),
        /* pathRelativizer */ Functions.<String>identity());
    junitJarRule = new PrebuiltJarRule(buildRuleParams,
        PATH_TO_JUNIT_JAR,
        Optional.of("lib/junit-4.11-sources.jar"),
        Optional.of("http://junit-team.github.io/junit/javadoc/latest/"));
  }

  @Test
  public void testAbiKeyIsHashOfFileContents() throws IOException {
    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = junitJarRule.getBuildSteps(buildContext, buildableContext);

    // Execute the lone build step.
    assertEquals("CalculateAbiStep should be the only step.", 1, buildSteps.size());
    Step calculateAbiStep = buildSteps.get(0);
    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();
    int exitCode = calculateAbiStep.execute(executionContext);
    assertEquals("Step should execute successfully.", 0, exitCode);

    // Make sure the ABI key is set as expected.
    Sha1HashCode abiKey = junitJarRule.getAbiKey();
    HashCode hashForJar = ByteStreams.hash(
        Files.newInputStreamSupplier(
            new File(PATH_TO_JUNIT_JAR)),
        Hashing.sha1());
    assertEquals("ABI key should be the sha1 of the file contents.",
        hashForJar.toString(),
        abiKey.toString());

    assertEquals(
        "Executing the step should record the ABI key as metadata.",
        ImmutableMap.of(
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
