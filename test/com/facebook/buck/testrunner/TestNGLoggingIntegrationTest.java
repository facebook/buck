/*
 * Copyright 2015-present Facebook, Inc.
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

public class TestNGLoggingIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testng_logging", temp, true);
    workspace.setUp();
  }

  @Test
  public void logOutputIsOnlyReportedForTestWhichFails() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//:testng-logging");
    result.assertTestFailure();

    // stdout should get all debug messages and up when a test fails.
    String testOutput = result.getStderr();

    // testOutput should get info messages and up when a test fails.
    assertThat(testOutput, containsString("This is an error in a failing test"));
    assertThat(testOutput, containsString("This is a warning in a failing test"));
    assertThat(testOutput, containsString("This is an info message in a failing test"));
    assertThat(testOutput, not(containsString("This is a debug message in a failing test")));
    assertThat(testOutput, not(containsString("This is a verbose message in a failing test")));

    // None of the messages printed in the passing test should be in the output.
    assertThat(testOutput, not(containsString("in a passing test")));
  }

  @Test
  public void logParametersForFailingTest() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//:testng-test-output-parameters");
    result.assertTestFailure();

    String testOutput = result.getStderr();
    // Failing test params are logged
    assertThat(testOutput, containsString("failingTestWithParams (1, 2)"));
    assertThat(testOutput, containsString("failingTestWithParams (2, 3)"));
    assertThat(testOutput, containsString("failingTestWithParams (3, 4)"));
    // Don't log test params for passing test
    assertThat(testOutput, not(containsString("failingTestWithParams (0, 0)")));
  }
}
