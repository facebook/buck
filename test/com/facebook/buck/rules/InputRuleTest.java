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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class InputRuleTest {

  private InputRule inputRule;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    inputRule = InputRule.inputPathAsInputRule("src/com/facebook/buck/cli/Main.java",
        projectFilesystem.getPathRelativizer());
  }

  @Test
  public void testBuildTargetForInputRule() {
    assertEquals("//src/com/facebook/buck/cli/Main.java:Main.java",
        inputRule.getBuildTarget().getFullyQualifiedName());
  }

  @Test
  public void testGetPathToOutputFileIsRelative() {
    assertEquals("src/com/facebook/buck/cli/Main.java", inputRule.getPathToOutputFile());
  }

  @Test
  public void testGetInputsIsEmpty() {
    assertTrue("Even though inputRule is itself an InputRule, its getInputs() should be empty.",
        Iterables.isEmpty(inputRule.getInputs()));
  }

  @Test
  public void testGetBuildResultTypeIsByDefinition() {
    assertEquals(BuildRuleSuccess.Type.BY_DEFINITION, inputRule.getBuildResultType());
  }

  @Test
  public void testToStringIsFullyQualifiedName() {
    assertEquals(inputRule.getFullyQualifiedName(), inputRule.toString());
  }
}
