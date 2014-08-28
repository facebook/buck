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
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Maps;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AssumptionViolationsTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
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
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.PassingTest" + type);
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "PASS", 1, 0, 0, "com.example.PassingTest" + type)));
    assertThat(output, containsString(
        "TESTS PASSED"));
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
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.FailingTest" + type,
        "--filter", "com.example.PassingTest" + type);
    result.assertTestFailure();
    String output = result.getStderr();
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "FAIL", 0, numSkipped, 1, "com.example.FailingTest" + type)));
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "PASS", 1, 0, 0, "com.example.PassingTest" + type)));
    assertThat(output,
        containsString("TESTS FAILED"));
  }

  @Test
  public void shouldIndicateAssumptionViolations() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--all",
        "--filter", "com.example.SomeAssumptionViolationsTestJunit",
        "--filter", "com.example.PassingTestJunit");
    result.assertSuccess();
    String output = result.getStderr();
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "ASSUME", 1, 2, 0, "com.example.SomeAssumptionViolationsTestJunit")));
    assertThat(output, containsRegex(createBuckTestOutputLineRegex(
        "PASS", 1, 0, 0, "com.example.PassingTestJunit")));
    assertThat(output, containsString(
        "TESTS PASSED (with some assumption violations)"));
  }

  @Test
  public void shouldSkipIndividualTestsWithDistinctErrorMessages() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--filter", "Test(A|B)Junit");
    List<BuckEvent> capturedEvents = result.getCapturedEvents();

    Map<String, String> output = Maps.newHashMap();
    IndividualTestEvent.Finished resultsEvent = null;
    for (BuckEvent event : capturedEvents) {
      if (!(event instanceof IndividualTestEvent.Finished)) {
        continue;
      }
      resultsEvent = (IndividualTestEvent.Finished) event;
      for (TestCaseSummary testCaseSummary : resultsEvent.getResults().getTestCases()) {
        for (TestResultSummary testResultSummary : testCaseSummary.getTestResults()) {
          String id = String.format(
              "%s#%s",
              testResultSummary.getTestCaseName(),
              testResultSummary.getTestName());
          // null for success
          // "A1:FAIL" for other types of result
          String message = null;
          if (testResultSummary.getType() != ResultType.SUCCESS) {
            StringBuilder builder = new StringBuilder();
            builder.append(testResultSummary.getMessage());
            builder.append(':');
            builder.append(testResultSummary.getType());
            message = builder.toString();
          }
          output.put(id, message);
        }
      }
    }
    assertThat("There should have been a test-results event", resultsEvent, notNullValue());

    assertThat("A1 should pass", output.get("com.example.AssumptionTestAJunit#test1"), nullValue());
    assertThat("A2 should pass", output.get("com.example.AssumptionTestAJunit#test2"), nullValue());
    assertThat("B3 should pass", output.get("com.example.AssumptionTestBJunit#test3"), nullValue());

    assertThat(
        "A3 should skip",
        output.get("com.example.AssumptionTestAJunit#test3"),
        equalTo("A3:ASSUMPTION_VIOLATION"));

    assertThat(
        "B1 should skip",
        output.get("com.example.AssumptionTestBJunit#test1"),
        equalTo("B1:ASSUMPTION_VIOLATION"));

    assertThat(
        "B2 should skip",
        output.get("com.example.AssumptionTestBJunit#test2"),
        equalTo("B2:ASSUMPTION_VIOLATION"));

    assertThat(
        "B4 should fail",
        output.get("com.example.AssumptionTestBJunit#test4"),
        equalTo("B4:FAILURE"));
  }
}
