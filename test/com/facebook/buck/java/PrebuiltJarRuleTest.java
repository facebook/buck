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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class PrebuiltJarRuleTest {

  private PrebuiltJarRule junitJarRule;

  @Before
  public void setUp() {
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        new BuildTarget(new File("lib/BUCK"), "//lib", "junit"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibilityPatterns */ ImmutableSet.of(BuildTargetPattern.MATCH_ALL),
        /* pathRelativizer */ Functions.<String>identity());
    junitJarRule = new PrebuiltJarRule(buildRuleParams,
        "lib/junit-4.11.jar",
        Optional.of("lib/junit-4.11-sources.jar"),
        Optional.of("http://junit-team.github.io/junit/javadoc/latest/"));
  }

  @Test
  public void testAbiKeyIsSameAsRuleKey() {
    assertEquals(
        junitJarRule.getRuleKey().toString(),
        junitJarRule.getAbiKey().get().getHash());
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
