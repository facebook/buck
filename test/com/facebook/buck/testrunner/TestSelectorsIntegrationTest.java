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

package com.facebook.buck.testrunner;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class TestSelectorsIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_selectors", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldFailWithoutAnySelectors() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure("Some tests fail");
    assertThat(result.getStderr(), containsString("TESTS FAILED: 1 FAILURE"));
  }

  @Test
  public void shouldRunASingleTest() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand("test", "--all", "--filter", "com.example.clown.FlowerTest");
    result.assertSuccess("The test passed");
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

  @Test
  public void shouldPassWhenUnselectingEntireFailingClass() throws IOException {
    String testSelector = "!com.example.clown.PrimeMinisterialDecreeTest";
    String[] command1 = {"test", "--all", "--test-selectors", testSelector};
    ProcessResult result = workspace.runBuckCommand(command1);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldPassWhenUnselectingTheFailingMethod() throws IOException {
    String[] command = {"test", "--all", "--test-selectors", "!#formAllianceWithClowns"};
    ProcessResult result = workspace.runBuckCommand(command);
    result.assertSuccess("All tests pass");
    assertTestsPassed(result);
    assertThatATestRan(result, "com.example.clown.CarTest");
    assertNotCached(result);
  }

  @Test
  public void shouldNotMatchMethodsUsingPrefixAlone() throws IOException {
    String[] command = {"test", "--all", "--test-selectors", "#test"};
    ProcessResult result = workspace.runBuckCommand(command);
    assertNoTestsRan(result);
    result.assertSuccess("Should pass, albeit because nothing actually ran");
  }

  @Test
  public void shouldReportRegularExpressionErrors() throws IOException {
    String error = "Regular expression error in 'Clown(': Unclosed group near index 7";
    try {
      workspace.runBuckCommand("test", "--all", "--filter", "Clown(");
      fail("Did not catch expected exception!");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), containsString(error));
    }
  }

  @Test
  public void shouldFilterFromFile() throws IOException {
    Path testSelectorsFile = workspace.getPath("test-selectors.txt");
    String arg = String.format("@%s", testSelectorsFile.toAbsolutePath().toString());
    ProcessResult result = workspace.runBuckCommand("test", "--all", "--filter", arg);
    result.assertSuccess();
  }

  @Test
  // TODO(ttsugrii): test expects a failure due to multiple filter arguments, but it actually
  // fails because bar/BUCK file does not exist :(
  @Ignore(value = "This is is broken since the assert succeeds for a completely unrelated reason.")
  public void shouldFailWithMultipleStringsGivenToFilter() throws IOException {
    String[] goodArgs = {"test", "--filter", "QQ", "//test/com/example/clown:clown"};
    String goodMessage = "Should pass, because the target is real and no test matches the filter.";

    String[] failArgs = {"test", "--filter", "QQ", "bar", "//test/com/example/clown:clown"};
    String failMessage = "Should fail, because two arguments are given to --filter!";

    workspace.runBuckCommand(goodArgs).assertSuccess(goodMessage);
    workspace.runBuckCommand(failArgs).assertFailure(failMessage);
  }

  @Test
  public void assertNoSummariesForASingleTest() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//test/com/example/clown:clown");
    assertNoTestSummaryShown(result);
  }

  @Test
  public void nestedClassTestSelectionWorks() throws IOException {
    ProcessResult filteredTestResult =
        workspace.runBuckCommand(
            "test", "//test/com/example/clown:clown", "-f", "NestedClassTest$#");
    filteredTestResult.assertSuccess(); // should run
    assertThat(filteredTestResult.getStderr(), containsString("NO TESTS RAN"));

    filteredTestResult =
        workspace.runBuckCommand(
            "test", "//test/com/example/clown:clown", "-f", "NestedClassTest\\$FirstInnerTest#");
    filteredTestResult.assertSuccess(); // should run
    assertThat(filteredTestResult.getStderr(), containsString("1 Passed"));
  }

  private void assertNotCached(ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("CACHED")));
  }

  private void assertThatATestRan(ProcessResult result, String testName) {
    assertThat(result.getStderr(), containsString(testName));
  }

  private void assertNoTestsRan(ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("com.example.clown.CarTest")));
    assertThat(result.getStderr(), not(containsString("com.example.clown.FlowerTest")));
    assertThat(
        result.getStderr(), not(containsString("com.example.clown.PrimeMinisterialDecreeTest")));
    assertThat(result.getStderr(), not(containsString("com.example.clown.ShoesTest")));
    assertThat(result.getStderr(), containsString("NO TESTS RAN"));
  }

  private void assertTestsPassed(ProcessResult result) {
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

  private void assertNoTestSummaryShown(ProcessResult result) {
    assertThat(result.getStderr(), not(containsString("Results for ")));
  }
}
