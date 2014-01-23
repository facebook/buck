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

package com.facebook.buck.test.labels;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class LabelsIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUpWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "labels", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldFailWithDashDashAll() throws IOException {
    assertTestsFail("test", "--all");
  }

  @Test
  public void shouldFailWithExplicitTargetsThatReferToFailingTests() throws IOException {
    assertTestsFail("test","//test:geometry", "//test:orientation");
  }

  @Test
  public void shouldPassWithDashDashAllWhenExcludingUnscientificTests() throws IOException {
    assertTestsPass("test", "--all", "--exclude", "unscientific");
  }

  @Test
  public void shouldPassWithDashDashAllWhenIncludingOnlyScientificTests() throws IOException {
    assertTestsPass("test", "--all", "--include", "scientific");
  }

  /**
   * This will fail because, as stated in TestCommand.java:
   *   "We always want to run the rules that are given on the command line. Always."
   */
  @Test
  public void shouldFailBecauseWeExplicitlyAskedForAFailingTestToRunEvenThoughWeTriedToExcludeIt()
      throws IOException {
    assertTestsFail("test", "//test:geometry", "//test:orientation", "--exclude", "unscientific");
  }

  @Test
  public void shouldIgnoreFailingTestWithTheCorrectLabelConjunction() throws IOException {
    // Both the passing test (PhotonsTest) and the failing test (EarthIsFlatTest) are labelled
    // testy, so asking for all tests that match "testy OR lighty" will fail.
    //
    // NB: A bug in the way args are parsed means that even though "testy light" is a single arg
    // here, it is split into multiple labels in by TestCommandOptions.
    assertTestsFail("test", "--all", "--include", "testy lighty");

    // ...but "testy AND lighty" only matches the passing test.
    assertTestsPass("test", "--all", "--include", "testy+lighty");
  }

  private void assertTestsFail(String... args) throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(args);
    result.assertExitCode(1);
    assertThat(result.getStderr(), containsString("Earth should be flat!"));
    assertThat(result.getStderr(), containsString("TESTS FAILED: 1 Failures"));
  }

  private void assertTestsPass(String... args) throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(args);
    result.assertExitCode(0);
    assertThat(result.getStderr(), containsString("TESTS PASSED"));
  }

}
