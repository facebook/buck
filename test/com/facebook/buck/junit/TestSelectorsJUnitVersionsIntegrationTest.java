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

package com.facebook.buck.junit;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class TestSelectorsJUnitVersionsIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test_selectors_junit_versions", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldRunEveryTest() throws IOException {
    Set<String> expectedPassingTests = ImmutableSet.of(
        "com.example.TestA",
        "com.example.TestB",
        "com.example.TestC",
        "com.example.TestD");
    assertPassingTests(expectedPassingTests, ImmutableList.of("test", "--all"));
  }

  @Test
  public void shouldOnlyRunOneTestFromTheNewerJUnit() throws IOException {
    assertPassingTests(
        ImmutableSet.of("com.example.TestA"),
        ImmutableList.of("test", "--all", "--filter", "com.example.TestA"));
  }

  @Test
  public void shouldOnlyRunOneTestFromTheOlderJUnit() throws IOException {
    assertPassingTests(
        ImmutableSet.of("com.example.TestC"),
        ImmutableList.of("test", "--all", "--filter", "com.example.TestC"));
  }

  private void assertPassingTests(
      Set<String> expectedPassingTests,
      List<String> buckArgs) throws IOException {
    String[] args = buckArgs.toArray(new String[buckArgs.size()]);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(args);
    String[] lines = result.getStderr().split("\n");
    ImmutableSet.Builder<String> actualPassingTestsBuilder = new ImmutableSet.Builder<>();
    for (String line : lines) {

      String[] tokens = line.split("\\s+");
      String testName = tokens[tokens.length - 1];
      if (testName.startsWith("com.example")) {
        String lineReason = String.format("Test %s should pass!", testName);
        assertThat(lineReason, tokens[0], equalTo("PASS"));
        actualPassingTestsBuilder.add(testName);
      }
    }
    ImmutableSet<String> actualPassingTests = actualPassingTestsBuilder.build();
    assertEquals("Expected tests were run (and passed)", expectedPassingTests, actualPassingTests);
  }
}
