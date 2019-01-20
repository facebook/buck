/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TestNGIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupSimpleTestNGWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_testng", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testThatSuccessfulTestNGTestWorks() throws IOException {
    ProcessResult simpleTestNGTestResult = workspace.runBuckCommand("test", "//test:simple-test");
    simpleTestNGTestResult.assertSuccess();
  }

  @Test
  public void testThatFailingBeforeTestFailsTest() throws IOException {
    ProcessResult simpleTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-before-test-fail");
    simpleTestNGTestResult.assertTestFailure();
  }

  @Test
  public void testThatSkipInBeforeTestWorks() throws IOException {
    ProcessResult skippedTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-before-test-skip");
    assertThat(
        skippedTestNGTestResult.getStderr(),
        containsString("NO TESTS RAN (assumption violations)"));
  }

  @Test
  public void testThatFailingTestNGTestWorks() throws IOException {
    ProcessResult failingTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-failing-test");
    failingTestNGTestResult.assertTestFailure();
  }

  @Test
  public void testThatSkippedTestNGTestFails() throws IOException {
    ProcessResult skippedTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-skipped-test");
    assertThat(
        skippedTestNGTestResult.getStderr(),
        containsString("TESTS PASSED (with some assumption violations)"));
  }

  @Test
  public void testSelectors() throws IOException {
    ProcessResult filteredTestNGTestResult =
        workspace.runBuckCommand("test", "//test:", "-f", "SimpleTest");
    filteredTestNGTestResult.assertSuccess();
    // should run SimpleTest
    assertThat(filteredTestNGTestResult.getStderr(), containsString("SimpleTest"));
    // should not run SimpleFailingTest
    assertThat(filteredTestNGTestResult.getStderr(), not(containsString("SimpleFailingTest")));
  }

  @Test
  public void testSelectorsBlacklistClass() throws IOException {
    ProcessResult filteredTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-test", "-f", "!SimpleTest");
    filteredTestNGTestResult.assertSuccess();
    // should not run SimpleTest
    assertThat(filteredTestNGTestResult.getStderr(), not(containsString("SimpleTest")));
  }

  @Test
  public void testSelectorsWhitelistMethod() throws IOException {
    ProcessResult filteredTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-test", "-f", "SimpleTest#defeat");
    filteredTestNGTestResult.assertSuccess();
    // should run SimpleTest#defeat only
    assertThat(filteredTestNGTestResult.getStderr(), containsString("1 Passed"));
  }

  @Test
  public void testSelectorsBlacklistMethod() throws IOException {
    ProcessResult filteredTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-test", "-f", "!SimpleTest#defeat");
    filteredTestNGTestResult.assertSuccess();
    // should run SimpleTest#victory only
    assertThat(filteredTestNGTestResult.getStderr(), containsString("1 Passed"));
  }

  @Test
  public void testThatInjectionWorks() throws IOException {
    ProcessResult injectionTestNGTestResult =
        workspace.runBuckCommand("test", "//test:injection-test");
    injectionTestNGTestResult.assertSuccess();
    // Make sure that we didn't just skip over the class.
    assertThat(injectionTestNGTestResult.getStderr(), containsString("1 Passed"));
  }

  @Test
  public void emptyMethodSelectorsRunsTests() throws IOException {
    ProcessResult filteredTestNGTestResult =
        workspace.runBuckCommand("test", "//test:", "-f", "SimpleTest#$");
    filteredTestNGTestResult.assertSuccess(); // should run SimpleTest
    assertThat(filteredTestNGTestResult.getStderr(), containsString("SimpleTest"));
    assertThat(filteredTestNGTestResult.getStderr(), containsString("2 Passed"));

    assertThat(filteredTestNGTestResult.getStderr(), not(containsString("SimpleFailingTest")));
  }
}
