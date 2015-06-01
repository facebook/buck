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

package com.facebook.buck.junit;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class LoggingIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void logOutputIsOnlyReportedForTestWhichFails() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "test_with_logging",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:logging");
    result.assertTestFailure();

    // stdout should get all debug messages and up when a test fails.
    String testOutput = result.getStderr();
    String[] testOutputBeforeAndAfterDebugLogs = testOutput.split(
        JUnitRunner.JUL_DEBUG_LOGS_HEADER);
    assertThat(testOutputBeforeAndAfterDebugLogs, arrayWithSize(2));
    String testOutputAfterDebugLogs = testOutputBeforeAndAfterDebugLogs[1];
    String[] testOutputBeforeAndAfterErrorLogs =
        testOutputAfterDebugLogs.split(JUnitRunner.JUL_ERROR_LOGS_HEADER);
    assertThat(testOutputBeforeAndAfterErrorLogs, arrayWithSize(2));
    String debugLogs = testOutputBeforeAndAfterErrorLogs[0];
    String errorLogs = testOutputBeforeAndAfterErrorLogs[1];

    // debugLogs should get debug messages and up when a test fails.
    assertThat(debugLogs, containsString("This is an error in a failing test"));
    assertThat(debugLogs, containsString("This is a warning in a failing test"));
    assertThat(debugLogs, containsString("This is an info message in a failing test"));
    assertThat(debugLogs, containsString("This is a debug message in a failing test"));
    assertThat(debugLogs, not(containsString("This is a verbose message in a failing test")));

    // errorLogs should get warnings and errors only when a test fails.
    assertThat(errorLogs, containsString("This is an error in a failing test"));
    assertThat(errorLogs, containsString("This is a warning in a failing test"));
    assertThat(errorLogs, not(containsString("This is an info message in a failing test")));
    assertThat(errorLogs, not(containsString("This is a debug message in a failing test")));
    assertThat(errorLogs, not(containsString("This is a verbose message in a failing test")));

    // None of the messages printed in the passing test should be in the output.
    assertThat(debugLogs, not(containsString("in a passing test")));
    assertThat(errorLogs, not(containsString("in a passing test")));
  }
}
