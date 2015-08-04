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
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class QueryCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "deps(//example:one)");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one-transitive-deps"), result.getStdout());
  }

  @Test
  public void testGetTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(//example:one)");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-one-testsof"), result.getStdout());
  }

  @Test
  public void testGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(//example:two, 1))");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-two-deps-tests.json"), result.getStdout());
  }

  @Test
  public void testGetTestsFromSelfAnd2LevelDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(//example:two, 2))");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-two-deps2lvl-tests.json"), result.getStdout());
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(deps(%s, 1))",
        "//example:two");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-two-deps-tests"), result.getStdout());
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(%s, 1))",
        "//example:two");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-two-deps-tests-map.json"), result.getStdout());
  }

  @Test
  public void testMultipleGetAllTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_dependencies", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(%s))",
        "//example:one",
        "//example:two",
        "//example:three",
        "//example:four",
        "//example:five",
        "//example:six");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-all-deps-tests-map.json"), result.getStdout());
  }
}
