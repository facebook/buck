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

public class AuditDependenciesCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testRunningWithNoParameterCausesBuildError() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies");
    result.assertFailure();
    assertEquals("BUILD FAILED: Must specify at least one build target.\n", result.getStderr());
  }

  @Test
  public void testImmediateDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies", "//example:one");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one"), result.getStdout());
  }

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "dependencies",
        "--transitive",
        "//example:one");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one-transitive"), result.getStdout());
  }

  @Test
  public void testJSONOutput() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "dependencies",
        "--json",
        "//example:one");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one.json"), result.getStdout());
  }

  @Test
  public void testExtraDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies", "//example:five");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-five"), result.getStdout());
  }

  @Test
  public void testTestFlagReturnsTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "dependencies",
        "--include-tests",
        "//example:four");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-four-tests"), result.getStdout());
  }

  @Test
  public void testTestFlagWithTransitiveShowsAllTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "dependencies",
        "--include-tests",
        "--transitive",
        "//example:one");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one-transitive-tests"), result.getStdout());
  }

  @Test
  public void testTestsThatArentNecessarilyIncludedInOriginalParseAreIncludedInOutput()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand(
        "audit",
        "dependencies",
        "--include-tests",
        "--transitive",
        "//lib/lib1:lib1");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-lib1-transitive-tests"), result.getStdout());
  }
}
