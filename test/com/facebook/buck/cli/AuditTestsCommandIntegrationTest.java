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

package com.facebook.buck.cli;

import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class AuditTestsCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testRunningWithNoParameterCausesBuildError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "tests");
    result.assertExitCode("missing parameter is error", ExitCode.COMMANDLINE_ERROR);
    assertThat(result.getStderr(), containsString("must specify at least one build target"));
  }

  @Test
  public void testTestsWithNoFlagsReturnsOnlyThatRulesTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "tests", "//example:one");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestsWithMultipleTargetParametersPrintsTestsForAllTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "tests", "//example:four", "//example:six");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-four-six"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestsPrintsTestEvenIfNotContainedInSameFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "tests", "//lib/lib1:lib1");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-lib1"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testPassingTheJSONFlagCausesJSONOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "tests",
            "--json",
            "//example:one",
            "//example:two",
            "//example:three",
            "//example:four",
            "//example:five",
            "//example:six");
    result.assertSuccess();
    String expected = workspace.getFileContents("stdout-one-two-three-four-five-six.json");
    assertThat(expected, equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestsWithMultipleTargetParametersExcludesDuplicateOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "tests", "//example:four", "//example:seven");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-four-seven"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }
}
