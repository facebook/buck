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

package com.facebook.buck.testrunner;

import static com.facebook.buck.testutil.OutputHelper.createBuckTestOutputLineRegex;
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AssumptionViolationsTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "assumption_violations", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldPassWithASimplePassingTestTestNG() throws IOException {
    shouldPassWithASimplePassingTest("TestNG");
  }

  @Test
  public void shouldPassWithASimplePassingTestJunit() throws IOException {
    shouldPassWithASimplePassingTest("Junit");
  }

  private void shouldPassWithASimplePassingTest(String type) throws IOException {
    ProcessResult result =
        workspace.runBuckCommand("test", "--all", "--filter", "com.example.PassingTest" + type);
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.PassingTest" + type)));
    assertThat(output, containsString("TESTS PASSED"));
  }

  @Test
  public void shouldFailIfOneTestFailsJunit() throws IOException {
    shouldFailIfOneTestFails("Junit", 1);
  }

  @Test
  public void shouldFailIfOneTestFailsTestNG() throws IOException {
    shouldFailIfOneTestFails("TestNG", 0);
  }

  private void shouldFailIfOneTestFails(String type, int numSkipped) throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--all",
            "--filter",
            "com.example.FailingTest" + type,
            "--filter",
            "com.example.PassingTest" + type);
    result.assertTestFailure();
    String output = result.getStderr();
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex(
                "FAIL", 0, numSkipped, 1, "com.example.FailingTest" + type)));
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.PassingTest" + type)));
    assertThat(output, containsString("TESTS FAILED"));
  }

  @Test
  public void shouldIndicateAssumptionViolations() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--all",
            "--filter",
            "com.example.SomeAssumptionViolationsTestJunit",
            "--filter",
            "com.example.PassingTestJunit");
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex(
                "ASSUME", 1, 2, 0, "com.example.SomeAssumptionViolationsTestJunit")));
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.PassingTestJunit")));
    assertThat(output, containsString("TESTS PASSED (with some assumption violations)"));
  }

  @Test
  public void shouldIndicateAssumptionViolationsBeforeClass() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//test:tests3");
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(
        output,
        containsRegex(
            createBuckTestOutputLineRegex(
                "ASSUME", 0, 1, 0, "com.example.AssumptionTestBeforeClassJunit412")));
  }
}
