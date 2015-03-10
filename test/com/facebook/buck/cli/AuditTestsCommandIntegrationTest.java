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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AuditTestsCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testRunningWithNoParameterCausesBuildError() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "tests");
    result.assertFailure();
    assertEquals("BUILD FAILED: Must specify at least one build target.\n", result.getStderr());
  }

  @Test
  public void testTestsWithNoFlagsReturnsOnlyThatRulesTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "tests", "//example:one");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one"), result.getStdout());
  }

  @Test
  public void testTestsWithMultipleTargetParametersPrintsTestsForAllTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "tests",
        "//example:four",
        "//example:six");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-four-six"), result.getStdout());
  }

  @Test
  public void testTestsPrintsTestEvenIfNotContainedInSameFile()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "tests",
        "//lib/lib1:lib1");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-lib1"), result.getStdout());
  }

  @Test
  public void testPassingTheJSONFlagCausesJSONOutput() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_tests", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
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
    assertEquals(expected, result.getStdout());
  }
}
