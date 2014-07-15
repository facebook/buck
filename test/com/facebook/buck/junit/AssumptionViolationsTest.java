/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.facebook.buck.testutil.OutputHelper.createBuckTestOutputLineRegex;
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AssumptionViolationsTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "ignored_tests", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldPassWithASimplePassingTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.PassingTest");
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "PASS", 1, 0, 0, "com.example.PassingTest")));
    assertThat(output, containsString(
        "TESTS PASSED"));
  }

  @Test
  public void shouldIndicateAssumptionViolations() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.SomeAssumptionViolationsTest",
        "--filter", "com.example.PassingTest");
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "ASSUME", 1, 2, 0, "com.example.SomeAssumptionViolationsTest")));
    assertThat(output, containsRegex(
        createBuckTestOutputLineRegex(
            "PASS", 1, 0, 0, "com.example.PassingTest")));
    assertThat(output, containsString(
        "TESTS PASSED (with some assumption violations)"));
  }

  @Test
  public void shouldFailIfOneTestFails() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.FailingTest",
        "--filter", "com.example.PassingTest");
    result.assertTestFailure();
    String output = result.getStderr();
    assertThat(output, containsRegex(
        createBuckTestOutputLineRegex(
            "FAIL", 0, 1, 1, "com.example.FailingTest")));
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "PASS", 1, 0, 0, "com.example.PassingTest")));
    assertThat(output,
        containsString("TESTS FAILED"));
  }
}
