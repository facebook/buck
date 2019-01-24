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

import static com.facebook.buck.util.MoreStringsForTests.containsIgnoringPlatformNewlines;
import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class QueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static JsonNode parseJSON(String content) throws IOException {
    return ObjectMappers.READER.readTree(ObjectMappers.createParser(content));
  }

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-one-transitive-deps"))));
  }

  @Test
  public void testGetTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-one-testsof"))));
  }

  @Test
  public void testGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "--json", "testsof(deps(//example:two, 1))");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests.json")))));
  }

  @Test
  public void testGetTestsFromSelfAnd2LevelDependenciesJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "--json", "testsof(deps(//example:two, 2))");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps2lvl-tests.json")))));
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "testsof(deps(%s, 1))", "//example:two");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-two-deps-tests"))));
  }

  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "--json", "testsof(deps(%s, 1))", "//example:two");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests-map.json")))));
  }

  @Test
  public void testMultipleGetAllTestsFromSelfAndDirectDependenciesJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "deps(%s, 1) union testsof(%s)", "//example:one");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-one-direct-deps-tests"))));
  }

  @Test
  public void testGetTestsFromPackageTargetPattern() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//example:)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-pkg-pattern-testsof"))));
  }

  @Test
  public void testGetTestsFromRecursiveTargetPattern() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//...)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-recursive-pattern-testsof"))));
  }

  @Test
  public void testMultipleQueryGetTestsFromRecursiveTargetPatternJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "--json", "testsof(%s)", "//...", "//example:");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(
            equalTo(
                parseJSON(workspace.getFileContents("stdout-recursive-pkg-pattern-tests.json")))));
  }

  @Test
  public void testOwnerOne() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "owner('example/1.txt')");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-one-owner"))));
  }

  @Test
  public void testOwners() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "owner('example/1.txt') + owner('example/2.txt')");

    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//example:one"));
    assertThat(result.getStdout(), containsString("//example:two"));
  }

  @Test
  public void testOwnersWithInvalidFilesPrintsErrors() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "owner('odd_files/unowned.cpp') + owner('odd_files/missing.cpp') + owner('odd_files/non_file') + owner('example/1.txt')");

    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//example:one"));
    assertThat(
        result.getStderr(),
        containsString(
            "No owner was found for "
                + MorePaths.pathWithPlatformSeparators("odd_files/unowned.cpp")));
    assertThat(
        result.getStderr(),
        containsString(
            "File "
                + MorePaths.pathWithPlatformSeparators("odd_files/missing.cpp")
                + " does not exist"));
    assertThat(
        result.getStderr(),
        containsString(
            MorePaths.pathWithPlatformSeparators("odd_files/non_file") + " is not a regular file"));
  }

  @Test
  public void testFormatWithoutFormatString() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckCommand("query", "owner('example/1.txt')", "+", "owner('example/2.txt')");
    processResult.assertExitCode(ExitCode.COMMANDLINE_ERROR);

    assertThat(processResult.getStderr(), containsString("format arguments"));
    assertThat(processResult.getStderr(), containsString("%s"));
  }

  @Test
  public void testOwnerOneSevenJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "--json", "owner('%s')", "example/1.txt", "example/app/7.txt");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-one-seven-owner.json")))));
  }

  @Test
  public void testOwnerOnAbsolutePath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "owner(%s)", MorePaths.pathWithUnixSeparators("example/1.txt"));

    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//example:one"));
  }

  @Test
  public void testTestsofOwnerOneSevenJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "--json", "testsof(owner('%s'))", "example/1.txt", "example/app/7.txt");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-one-seven-tests-own.json")))));
  }

  @Test
  public void testKindStarTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "kind('.*_test', '//example/...')");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-recursive-pattern-kind"))));
  }

  @Test
  public void testKindNoResults() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "kind('python_library', deps(//example:one))");

    result.assertSuccess();
    assertThat(result.getStdout(), is(equalTo("")));
  }

  @Test
  public void testKindDepsDoesNotShowEmptyResultsJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--json",
            "kind('java_library', deps('%s') except '%s')",
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "rdeps(set(//example:one //example/app:seven), set(//example/app:seven //example:five))");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-five-seven-rdeps"))));
  }

  @Test
  public void testGetReverseDependenciesFormatSet() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "rdeps(set(//example:one //example/app:seven), %Ss)",
            "//example/app:seven",
            "//example:five");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-five-seven-rdeps"))));
  }

  @Test
  public void testMultipleGetTestsofDirectReverseDependenciesJSON() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
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

  @Test
  public void testGetSrcsAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "labels('srcs', '//example:one')");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        containsIgnoringPlatformNewlines(MorePaths.pathWithPlatformSeparators("example/1.txt")));
  }

  @Test
  public void testEvaluateFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "example/1.txt + example/2.txt");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                String.format(
                    "%s%n%s%n",
                    MorePaths.pathWithPlatformSeparators("example/1.txt"),
                    MorePaths.pathWithPlatformSeparators("example/2.txt")))));
  }

  @Test
  public void testUnionMultipleAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "labels('tests', '//example:four') + labels('srcs', '//example:five') "
                + "+ labels('srcs', '//example:six') - '//example:six'");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            containsIgnoringPlatformNewlines(
                "//example:four-application-tests\n"
                    + "//example:four-tests\n"
                    + MorePaths.pathWithPlatformSeparators("example/5.txt")
                    + "\n"
                    + MorePaths.pathWithPlatformSeparators("example/6.txt"))));
  }

  @Test
  public void testGetMultipleSrcsAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "--json", "labels('srcs', '%s')", "//example:");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(
            equalTo(
                parseJSON(
                    ("{\n"
                            + "  \"//example:\" : [\n"
                            + "    \"//example:six\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/1-test.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/1.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/2.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/3.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/4-application-test.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/4-test.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/4.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/5.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/6-test.txt")
                            + "\",\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/6.txt")
                            + "\"\n"
                            + "  ]\n"
                            + "}\n")
                        .replace("\\", "\\\\")))));
  }

  @Test
  public void testOutputAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "//example:one + //example:four", "--output-attributes", "name", "deps");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-name-deps-multi-attr-out.json")))));
  }

  @Test
  public void testOutputAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "//example:one + //example:four",
            "--output-attribute",
            "name",
            "--output-attribute",
            "deps");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-name-deps-multi-attr-out.json")))));
  }

  @Test
  public void testResolveAliasOutputAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "testsof(app)", "--output-attributes", "name", "buck.type");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-seven-alias-testsof-attrs.json")))));
  }

  @Test
  public void testTestsofDoesNotModifyGraph() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "deps(testsof(deps('%s')))", "//example:one");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-deps-testsof-deps"))));
  }

  @Test
  public void testFilterFour() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "filter('four', '//example/...')");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-filter-four"))));
  }

  @Test
  public void testAllPathsDepsOneToFour() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "allpaths(//example:one, //example:four)", "--output-attributes", "deps");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-allpaths-one-four.json")))));
  }

  @Test
  public void testAllPathsDepsOneToFiveSix() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--dot",
            "allpaths(deps(//example:one, 1), set(//example:five //example:six))");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-allpaths-deps-one-to-five-six.dot"))));
  }

  @Test
  public void testAllPathsDepsOneToFiveSixFormatSet() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--dot",
            "allpaths(deps(//example:one, 1), %Ss)",
            "//example:five",
            "//example:six");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-allpaths-deps-one-to-five-six.dot"))));
  }

  @Test
  public void testDotOutputForDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "--dot", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-deps-one.dot"))));

    result = workspace.runBuckCommand("query", "--dot", "--bfs", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-bfs-deps-one.dot"))));
  }

  @Test
  public void testDotOutputWithAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "--dot", "deps(//example:one)", "--output-attributes", "name", "buck.type");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-deps-one-with-attributes.dot"))));
  }

  @Test
  public void testRankOutputForDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "--output", "minrank", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-minrank-deps-one"))));

    result = workspace.runBuckCommand("query", "--output", "maxrank", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-maxrank-deps-one"))));
  }

  @Test
  public void testRankOutputWithAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "deps(//example:one)", "--output", "minrank", "--output-attributes", "name");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-minrank-deps-one-with-attributes.json"))));

    result =
        workspace.runBuckCommand(
            "query", "deps(//example:one)", "--output", "maxrank", "--output-attributes", "name");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-maxrank-deps-one-with-attributes.json"))));
  }

  @Test
  public void testRankOutputWithAttributesIgnoresFlavors() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "deps(//example:one#doc)",
            "--output",
            "minrank",
            "--output-attributes",
            "name");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-minrank-deps-one-with-attributes.json"))));

    result =
        workspace.runBuckCommand(
            "query",
            "deps(//example:one#doc)",
            "--output",
            "maxrank",
            "--output-attributes",
            "name");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-maxrank-deps-one-with-attributes.json"))));
  }

  class ParserProfileFinder extends SimpleFileVisitor<Path> {

    private Path profilerPath = null;

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
      if (path.toString().contains("parser-profiler")) {
        profilerPath = path;
        return FileVisitResult.TERMINATE;
      } else {
        return FileVisitResult.CONTINUE;
      }
    }

    public Path getProfilerPath() {
      return profilerPath;
    }
  }

  @Test
  public void testQueryProfileParser() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "deps(//example:one)", "--profile-buck-parser");
    result.assertSuccess();

    Path logPath = tmp.getRoot().resolve("buck-out").resolve("log");
    ParserProfileFinder parserProfileFinder = new ParserProfileFinder();
    Files.walkFileTree(logPath, parserProfileFinder);

    assertNotNull("Profiler log not found", parserProfileFinder.getProfilerPath());

    String content = new String(Files.readAllBytes(parserProfileFinder.getProfilerPath()));
    assertTrue(content.contains("Total:"));
    assertTrue(content.contains("# Parsed "));
    assertTrue(content.contains("# Highlights"));
    assertTrue(content.contains("# More details"));
  }

  @Test
  public void testQueryProfileSkylark() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    Path skylarkProfile = tmp.newFile("skylark-profile");
    ProcessResult result =
        workspace.runBuckCommand(
            "query", "deps(//example:one)", "--skylark-profile-output=" + skylarkProfile);
    result.assertSuccess();

    byte[] content = Files.readAllBytes(skylarkProfile);
    assertTrue("Profile should not be empty", content.length > 0);
  }

  @Test
  public void testFilterAttrTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "attrfilter(tests, '//example:four-tests', '//example/...')");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//example:four\n")));
  }

  @Test
  public void testFilterAttrOutputAttributesTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "attrfilter(labels, 'e2e', '//example/...')",
            "--output-attributes",
            "buck.type",
            "srcs",
            "info_plist");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-attr-e2e-filter.json")))));
  }

  @Test
  public void testBuildFileFunction() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "buildfile(owner('example/1.txt'))");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        containsIgnoringPlatformNewlines(MorePaths.pathWithPlatformSeparators("example/BUCK")));
  }

  @Test
  public void testBuildFileFunctionJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--json",
            "buildfile(owner('%s'))",
            "example/app/lib/9.txt",
            "other/8-test.txt");

    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(
            equalTo(
                parseJSON(
                    ("{\n"
                            + "  \"example/app/lib/9.txt\": [\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("example/app/BUCK")
                            + "\"\n"
                            + "  ],\n"
                            + "  \"other/8-test.txt\": [\n"
                            + "    \""
                            + MorePaths.pathWithPlatformSeparators("other/BUCK")
                            + "\"\n"
                            + "  ]\n"
                            + "}\n")
                        .replace("\\", "\\\\")))));
  }

  @Test
  public void testInputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "inputs(//example:four-tests)");

    result.assertSuccess();
    assertThat(
        result.getStdout().trim(), is(MorePaths.pathWithPlatformSeparators("example/4-test.txt")));
  }

  @Test
  public void testInputsUsesPathsRelativeToRootCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command_cross_cell", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(workspace.resolve("cell1"), "query", "inputs(cell2//foo:test)");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        containsIgnoringPlatformNewlines(
            MorePaths.pathWithPlatformSeparators("../cell2/foo/foo.txt")));
  }

  @Test
  public void testInputsTwoTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "inputs(//example:four-tests + //example:one)");

    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                String.format(
                    "%s%n%s%n",
                    MorePaths.pathWithPlatformSeparators("example/4-test.txt"),
                    MorePaths.pathWithPlatformSeparators("example/1.txt")))));
  }

  @Test
  public void testOwnerCrossCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command_cross_cell", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            workspace.resolve("cell1"), "query", "owner(../cell2/foo/foo.txt)");
    result.assertSuccess();
    assertEquals("cell2//foo:test", result.getStdout().trim());
  }

  @Test
  public void testMultipleOwnersCrossingPackageBoundaryWithException() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "project.package_boundary_exceptions=owners_violating_package_boundary",
            "owner(owners_violating_package_boundary/inner/Source.java)");
    result.assertSuccess();
    assertThat(
        Splitter.on("\n").omitEmptyStrings().trimResults().splitToList(result.getStdout()),
        Matchers.containsInAnyOrder(
            "//owners_violating_package_boundary:lib",
            "//owners_violating_package_boundary/inner:lib"));
  }

  @Test
  public void testMultipleOwnersCrossingPackageBoundaryWithoutException() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "project.package_boundary_exceptions=",
            "owner(owners_violating_package_boundary/inner/Source.java)");
    result.assertSuccess();
    assertThat(
        Splitter.on("\n").omitEmptyStrings().trimResults().splitToList(result.getStdout()),
        Matchers.containsInAnyOrder("//owners_violating_package_boundary/inner:lib"));
  }

  @Test
  public void testTwoDifferentSetsPassedFromCommandLine() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query", "testsof(deps(%Ss)) union deps(%Ss)", "//example:four", "--", "//example:one");
    result.assertSuccess();

    List<String> output =
        Splitter.on("\n").omitEmptyStrings().trimResults().splitToList(result.getStdout());
    assertThat(output, Matchers.hasItems("//example:one", "//example:four-tests"));
    assertThat(output, Matchers.not(Matchers.hasItem("//example:one-tests")));
  }

  @Test
  public void testThreeDifferentSetsPassedFromCommandLine() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "testsof(deps(%Ss)) union (deps(%Ss) intersect deps(%Ss))",
            "//example:four",
            "--",
            "//example:one",
            "--",
            "//example:two");
    result.assertSuccess();

    List<String> output =
        Splitter.on("\n").omitEmptyStrings().trimResults().splitToList(result.getStdout());
    assertThat(output, Matchers.hasItems("//example:two", "//example:four-tests"));
    assertThat(
        output,
        Matchers.not(Matchers.hasItems("//example:one", "//example:one-tests", "//example:four")));
  }

  @Test
  public void testMultiQueryWorksWithOutputAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    String expectedString =
        workspace.getFileContents("stdout-output-attributes-and-multi-query.json");
    JsonNode expectedNode = new ObjectMapper().readTree(expectedString);

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "deps(%s)",
            "//example:",
            "//example:one",
            "//example:two",
            "--output-attributes",
            "name",
            "--output-attributes",
            "srcs");
    result.assertSuccess();

    JsonNode jsonNode = new ObjectMapper().readTree(result.getStdout());

    assertEquals(expectedNode, jsonNode);
  }

  @Test
  public void testMultiQueryWorksWithOutputAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    String expectedString =
        workspace.getFileContents("stdout-output-attributes-and-multi-query.json");

    JsonNode expectedNode = new ObjectMapper().readTree(expectedString);

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--output-attribute",
            "name",
            "--output-attribute",
            "srcs",
            "deps(%s)",
            "//example:",
            "//example:one",
            "//example:two");
    result.assertSuccess();

    JsonNode jsonNode = new ObjectMapper().readTree(result.getStdout());

    assertEquals(expectedNode, jsonNode);
  }

  @Test
  public void testListValuesFromConfigurableAttributesAreConcatenated() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:genrule_with_select_in_srcs",
            "--output-attributes",
            "srcs");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:genrule_with_select_in_srcs\" : {\n"
                    + "    \"srcs\" : [ \":c\", \":a\" ]\n"
                    + "  }\n"
                    + "}\n")));
  }

  @Test
  public void testStringValuesFromConfigurableAttributesAreConcatenated() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:genrule_with_select_in_cmd",
            "--output-attributes",
            "cmd");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:genrule_with_select_in_cmd\" : {\n"
                    + "    \"cmd\" : \"echo $(location :a) > $OUT\"\n"
                    + "  }\n"
                    + "}\n")));
  }

  @Test
  public void testIntegerValuesFromConfigurableAttributesAreConcatenated() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:java_test_with_select_in_timeout",
            "--output-attributes",
            "test_case_timeout_ms");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:java_test_with_select_in_timeout\" : {\n"
                    + "    \"test_case_timeout_ms\" : 13\n"
                    + "  }\n"
                    + "}\n")));

    result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=b",
            "//:java_test_with_select_in_timeout",
            "--output-attributes",
            "test_case_timeout_ms");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:java_test_with_select_in_timeout\" : {\n"
                    + "    \"test_case_timeout_ms\" : 14\n"
                    + "  }\n"
                    + "}\n")));
  }

  @Test
  public void testMapValuesFromConfigurableAttributesAreConcatenated() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:java_test_with_select_in_env",
            "--output-attributes",
            "env");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:java_test_with_select_in_env\" : {\n"
                    + "    \"env\" : {\n"
                    + "      \"var1\" : \"val1\",\n"
                    + "      \"var2\" : \"val2\",\n"
                    + "      \"vara\" : \"vala\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n")));

    result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=b",
            "//:java_test_with_select_in_env",
            "--output-attributes",
            "env");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:java_test_with_select_in_env\" : {\n"
                    + "    \"env\" : {\n"
                    + "      \"var1\" : \"val1\",\n"
                    + "      \"var2\" : \"val2\",\n"
                    + "      \"varb\" : \"valb\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n")));
  }

  @Test
  public void testExcludeIncompatibleTargetsFiltersTargetsByConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_incompatible_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "kind(genrule, //:)",
            "--target-platforms",
            "//:linux_platform",
            "--exclude-incompatible-targets");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//:a\n")));

    result =
        workspace.runBuckCommand(
            "query",
            "kind(genrule, //:)",
            "--target-platforms",
            "//:osx_platform",
            "--exclude-incompatible-targets");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//:b\n")));
  }
}
