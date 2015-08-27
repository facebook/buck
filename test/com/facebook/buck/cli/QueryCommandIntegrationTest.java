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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class QueryCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static JsonNode parseJSON(String content) throws IOException {
    return objectMapper.readTree(objectMapper.getJsonFactory().createJsonParser(content));
  }

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-one-transitive-deps"))));
  }

  @Test
  public void testGetTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(//example:one)");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalTo(workspace.getFileContents("stdout-one-testsof"))));
  }

  @Test
  public void testGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(//example:two, 1))");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests.json")))));
  }

  @Test
  public void testGetTestsFromSelfAnd2LevelDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(//example:two, 2))");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps2lvl-tests.json")))));
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(deps(%s, 1))",
        "//example:two");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-two-deps-tests"))));
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(deps(%s, 1))",
        "//example:two");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests-map.json")))));
  }

  @Test
  public void testMultipleGetAllTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
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
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-all-deps-tests-map.json")))));
  }

  @Test
  public void testMultipleQueryFormatGetDirectDependenciesAndTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "deps(%s, 1) union testsof(%s)",
        "//example:one");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-one-direct-deps-tests"))));
  }

  @Test
  public void testGetTestsFromPackageTargetPattern() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(//example:)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-pkg-pattern-testsof"))));
  }

  @Test
  public void testGetTestsFromRecursiveTargetPattern() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "testsof(//...)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-recursive-pattern-testsof"))));
  }

  @Test
  public void testMultipleQueryGetTestsFromRecursiveTargetPatternJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(%s)",
        "//...",
        "//example:");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(
            parseJSON(workspace.getFileContents("stdout-recursive-pkg-pattern-tests.json")))));
  }

  @Test
  public void testOwnerOne() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "owner('example/1.txt')");

    result.assertSuccess();
    assertThat(result.getStdout(), is(equalTo(workspace.getFileContents("stdout-one-owner"))));
  }

  @Test
  public void testOwnerOneSevenJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "owner('%s')",
        "example/1.txt",
        "example/app/7.txt");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-one-seven-owner.json")))));
  }

  @Test
  public void testTestsofOwnerOneSevenJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(owner('%s'))",
        "example/1.txt",
        "example/app/7.txt");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-one-seven-tests-own.json")))));
  }

  @Test
  public void testKindStarTest() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "kind('.*_test', '//example/...')");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-recursive-pattern-kind"))));
  }

  @Test
  public void testKindNoResults() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "kind('python_library', deps(//example:one))");

    result.assertSuccess();
    assertThat(result.getStdout(), is(equalTo("")));
  }

  @Test
  public void testKindDepsDoesNotShowEmptyResultsJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "kind('apple_library', deps('%s') except '%s')",
        "//example:one",
        "//example:five",
        "//example/app:seven");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-one-five-seven-kind-deps.json")))));
  }

  @Test
  public void testGetReverseDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "rdeps(set(//example:one //example/app:seven), set(//example/app:seven //example:five))");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalTo(workspace.getFileContents("stdout-five-seven-rdeps"))));
  }

  @Test
  public void testMultipleGetTestsofDirectReverseDependenciesJSON() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "query_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "query",
        "--json",
        "testsof(rdeps(//example:one, '%s', 1))",
        "//example:two",
        "//example:four");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-four-tests-rdeps.json")))));
  }
}
