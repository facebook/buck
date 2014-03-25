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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestSelectorsIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test_selectors", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldFailWithoutAnySelectors() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure("Some tests fail");
    assertThat(result.getStderr(), containsString("TESTS FAILED: 1 Failures"));
  }

  @Test
  public void shouldRunASingleTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--filter", "com.example.clown.FlowerTest");
    result.assertSuccess("The test passed");
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

  @Test
  public void shouldPassWhenUnselectingEntireFailingClass() throws IOException {
    String testSelector = "!com.example.clown.PrimeMinisterialDecreeTest";
    String[] command1 = {"test", "--all", "--test-selectors", testSelector};
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(command1);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldPassWhenUnselectingTheFailingMethod() throws IOException {
    String[] command = {"test", "--all", "--test-selectors", "!#formAllianceWithClowns"};
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(command);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldNeverCacheWhenUsingSelectors() throws IOException {
    String[] commandWithoutSelector = {"test", "--all"};
    String[] commandWithSelector = {"test", "--all", "--test-selectors", "#testIsComical"};

    // Without selectors, the first run isn't cached and the second run is.
    ProjectWorkspace.ProcessResult result1 = workspace.runBuckCommand(commandWithoutSelector);
    assertNotCached(result1);
    assertOutputWithoutSelectors(result1);
    assertCached(workspace.runBuckCommand(commandWithoutSelector));

    // With a selector, runs are not cached.
    ProjectWorkspace.ProcessResult result2 = workspace.runBuckCommand(commandWithSelector);
    assertNotCached(result2);
    assertOutputWithSelectors(result2);
    assertNotCached(workspace.runBuckCommand(commandWithSelector));

    // But when we go back to not using a selector, the first run is uncached, and the second is.
    ProjectWorkspace.ProcessResult result3 = workspace.runBuckCommand(commandWithoutSelector);
    assertNotCached(result3);
    assertOutputWithoutSelectors(result3);
    assertCached(workspace.runBuckCommand(commandWithoutSelector));
  }

  @Test
  public void shouldReportRegularExpressionErrors() throws IOException {
    String error = "Regular expression error in 'Clown(': Unclosed group near index 6";
    try {
      workspace.runBuckCommand("test", "--all", "--filter", "Clown(");
      fail("Did not catch expected exception!");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), containsString(error));
    }
  }

  @Test
  public void shouldFilterFromFile() throws IOException {
    File testSelectorsFile = workspace.getFile("test-selectors.txt");
    String arg = String.format("@%s", testSelectorsFile.getAbsolutePath());
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test", "--all", "--filter", arg);
    result.assertSuccess();
  }

  @Test
  public void shouldFailWithMultipleStringsGivenToFilter() throws IOException {
    String[] goodArgs = {"test", "--filter", "QQ", "//test/com/example/clown:clown"};
    String goodMessage = "Should pass, because the target is real and no test matches the filter.";

    String[] failArgs = {"test", "--filter", "QQ", "bar", "//test/com/example/clown:clown"};
    String failMessage = "Should fail, because two arguments are given to --filter!";

    workspace.runBuckCommand(goodArgs).assertSuccess(goodMessage);
    workspace.runBuckCommand(failArgs).assertFailure(failMessage);
  }

  private void assertOutputWithSelectors(ProjectWorkspace.ProcessResult result) {
    String stderr = result.getStderr();
    assertThat(stderr, containsString(
        "PASS <100ms  1 Passed   0 Failed   com.example.clown.CarTest"));
    assertThat(stderr, containsString(
        "PASS <100ms  1 Passed   0 Failed   com.example.clown.FlowerTest"));
    assertThat(stderr, containsString(
        "PASS <100ms  1 Passed   0 Failed   com.example.clown.PrimeMinisterialDecreeTest"));
    assertThat(stderr, containsString(
        "PASS <100ms  1 Passed   0 Failed   com.example.clown.ShoesTest"));
  }

  private void assertOutputWithoutSelectors(ProjectWorkspace.ProcessResult result) {
    String stderr = result.getStderr();
    assertThat(stderr, containsString(
        "PASS <100ms  3 Passed   0 Failed   com.example.clown.CarTest"));
    assertThat(stderr, containsString(
        "PASS <100ms  3 Passed   0 Failed   com.example.clown.FlowerTest"));
    assertThat(stderr, containsString(
        "FAIL <100ms  5 Passed   1 Failed   com.example.clown.PrimeMinisterialDecreeTest"));
    assertThat(stderr, containsString(
        "PASS <100ms  2 Passed   0 Failed   com.example.clown.ShoesTest"));
  }

  private void assertCached(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), containsString("CACHED"));
  }

  private void assertNotCached(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("CACHED")));
  }

  private void assertThatATestRan(ProjectWorkspace.ProcessResult result, String testName) {
    assertThat(result.getStderr(), containsString(testName));
  }

  private void assertTestsPassed(ProjectWorkspace.ProcessResult result) {
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }
}
