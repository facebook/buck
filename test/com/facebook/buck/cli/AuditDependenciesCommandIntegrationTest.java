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

public class AuditDependenciesCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testRunningWithNoParameterCausesBuildError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies");
    result.assertExitCode("missing parameter is error", ExitCode.COMMANDLINE_ERROR);
    assertThat(result.getStderr(), containsString("must specify at least one build target"));
  }

  @Test
  public void testImmediateDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies", "//example:one");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "--transitive", "//example:one");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one-transitive"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testJSONOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "--json", "//example:one");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one.json"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testExtraDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "dependencies", "//example:five");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-five"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestFlagReturnsTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "--include-tests", "//example:four");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-four-tests"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestFlagWithTransitiveShowsAllTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit", "dependencies", "--include-tests", "--transitive", "//example:one");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one-transitive-tests"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTestsThatArentNecessarilyIncludedInOriginalParseAreIncludedInOutput()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit", "dependencies", "--include-tests", "--transitive", "//lib/lib1:lib1");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-lib1-transitive-tests"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testThatJSONOutputWithMultipleInputsIsGroupedByInput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit", "dependencies", "--json", "//example:two", "//example:three");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-two-three.json"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testThatTransitiveJSONOutputWithMultipleInputsIsGroupedByInput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "dependencies",
            "--transitive",
            "--json",
            "//example:one",
            "//example:two",
            "//example:three");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-one-two-three-transitive.json"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testOutputWithoutDuplicates() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "//example:two", "//example:three");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-two-three"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTransitiveDependenciesMultipleInputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "audit", "dependencies", "--transitive", "//example:two", "//example:three");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-two-three-transitive"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testDirectDependenciesIncludesExtraDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "//example:app-target");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-app-target-extra-deps"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testTransitiveDependenciesIncludesExtraDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "dependencies", "--transitive", "//example:app-library");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-app-library-transitive"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }
}
