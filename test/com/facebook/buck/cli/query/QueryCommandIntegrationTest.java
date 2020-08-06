/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli.query;

import static com.facebook.buck.cli.ThriftOutputUtils.edgesToStringList;
import static com.facebook.buck.cli.ThriftOutputUtils.nodesToStringList;
import static com.facebook.buck.util.MoreStringsForTests.containsIgnoringPlatformNewlines;
import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.query.thrift.DirectedAcyclicGraph;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class QueryCommandIntegrationTest {

  private static final Splitter NEWLINE_SPLITTER = Splitter.on('\n');
  private static final Joiner NEWLINE_JOINER = Joiner.on('\n');

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are the same as those in
   * the specified file. Note that sort order is not guaranteed by {@code buck query} unless it is
   * specified explicitly via {@code --sort-output}.
   */
  private void assertLinesMatch(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    // All lines in expected output files are sorted so sort the output from `buck query` before
    // comparing. Although query/--sort-output claims to sort labels by default, this does not
    // appear to be honored, in practice.
    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        normalizeContents(result.getStdout()));
  }

  /** @param contents lines from a text file to normalize via sorting */
  private String normalizeContents(String contents) {
    List<String> unsortedLines = NEWLINE_SPLITTER.splitToList(normalizeNewlines(contents));
    assertFalse("Output should have at least one blank line.", unsortedLines.isEmpty());
    assertEquals("", unsortedLines.get(unsortedLines.size() - 1));
    // Note that splitToList() returns an immutable list, so we must copy it to an ArrayList so we
    // can sort it. We ignore the "" entry at the end of the list.
    List<String> lines = new ArrayList<>(unsortedLines.subList(0, unsortedLines.size() - 1));
    lines.sort(String::compareTo);
    return NEWLINE_JOINER.join(lines) + "\n";
  }

  @Test
  public void testNormalizesRelativeBuildTargetsInQueries() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult topLevelRecursive = workspace.runBuckCommand("query", "testsof(...)");
    ProcessResult subdirRecursive = workspace.runBuckCommand("query", "testsof(example/app/...)");
    ProcessResult subdirPackage = workspace.runBuckCommand("query", "testsof(example/app:)");
    ProcessResult subdirTarget = workspace.runBuckCommand("query", "testsof(example/app:seven)");

    assertLinesMatch("stdout-recursive-pattern-testsof", topLevelRecursive, workspace);
    assertLinesMatch("stdout-subdir-recursive-pattern-testsof", subdirRecursive, workspace);
    assertLinesMatch("stdout-subdir-recursive-pattern-testsof", subdirPackage, workspace);
    assertLinesMatch("stdout-subdir-recursive-pattern-testsof", subdirTarget, workspace);
  }

  @Test
  public void testTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "deps(//example:one)");
    assertLinesMatch("stdout-one-transitive-deps", result, workspace);
  }

  @Test
  public void testGetTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//example:one)");
    assertLinesMatch("stdout-one-testsof", result, workspace);
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testGetTestsFromSelfAndDirectDependenciesJSON(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", jsonParam, "testsof(deps(//example:two, 1))");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests.json")))));
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testGetTestsFromSelfAnd2LevelDependenciesJSON(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", jsonParam, "testsof(deps(//example:two, 2))");
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
    assertLinesMatch("stdout-two-deps-tests", result, workspace);
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testMultipleQueryGetTestsFromSelfAndDirectDependenciesJSON(String jsonParam)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", jsonParam, "testsof(deps(%s, 1))", "//example:two");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-two-deps-tests-map.json")))));
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testMultipleGetAllTestsFromSelfAndDirectDependenciesJSON(String jsonParam)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            jsonParam,
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
    assertLinesMatch("stdout-one-direct-deps-tests", result, workspace);
  }

  @Test
  public void testGetTestsFromPackageTargetPattern() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//example:)");
    assertLinesMatch("stdout-pkg-pattern-testsof", result, workspace);
  }

  @Test
  public void testRelativeTargetsWorkForVariousQueries() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    workspace.setRelativeWorkingDirectory(Paths.get("example"));

    ProcessResult testsOfPackage = workspace.runBuckCommand("query", "testsof(:)").assertSuccess();
    ProcessResult testsOfRecursive =
        workspace.runBuckCommand("query", "testsof(...)").assertSuccess();
    ProcessResult testsOfSubdirRecursive = workspace.runBuckCommand("query", "testsof(app/...)");
    ProcessResult simpleDeps = workspace.runBuckCommand("query", "deps(:one)").assertSuccess();
    ProcessResult simpleSubdir = workspace.runBuckCommand("query", "app:seven").assertSuccess();

    assertLinesMatch("stdout-pkg-pattern-testsof", testsOfPackage, workspace);
    assertLinesMatch("stdout-recursive-pattern-testsof", testsOfRecursive, workspace);
    assertLinesMatch("stdout-subdir-recursive-pattern-testsof", testsOfSubdirRecursive, workspace);
    assertLinesMatch("stdout-one-transitive-deps", simpleDeps, workspace);
    assertEquals("//example/app:seven", simpleSubdir.getStdout().trim());
  }

  @Test
  public void testGetTestsFromRecursiveTargetPattern() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("query", "testsof(//...)");
    assertLinesMatch("stdout-recursive-pattern-testsof", result, workspace);
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testMultipleQueryGetTestsFromRecursiveTargetPatternJSON(String jsonParam)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", jsonParam, "testsof(%s)", "//...", "//example:");
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
    assertLinesMatch("stdout-one-owner", result, workspace);
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
  public void testOwnersFromSubdirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setRelativeWorkingDirectory(Paths.get("example", "app"));
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", "owner('../1.txt') + owner('7.txt')");

    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//example:one"));
    assertThat(result.getStdout(), containsString("//example/app:seven"));
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

  @Parameters(method = "getJsonParams")
  @Test
  public void testOwnerOneSevenJSON(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", jsonParam, "owner('%s')", "example/1.txt", "example/app/7.txt");

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
            "query", "owner(%s)", PathFormatter.pathWithUnixSeparators("example/1.txt"));

    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//example:one"));
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testTestsofOwnerOneSevenJSON(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", jsonParam, "testsof(owner('%s'))", "example/1.txt", "example/app/7.txt");

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
    assertLinesMatch("stdout-recursive-pattern-kind", result, workspace);
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

  @Parameters(method = "getJsonParams")
  @Test
  public void testKindDepsDoesNotShowEmptyResultsJSON(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            jsonParam,
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
    assertLinesMatch("stdout-five-seven-rdeps", result, workspace);
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
    assertLinesMatch("stdout-five-seven-rdeps", result, workspace);
  }

  /**
   * Tests for a bug where the combination of using instance equality for target nodes and using
   * multiple separate calls into the parse, each which invalidate the cache nodes with inputs under
   * symlinks, triggers a crash in `buck query` when it sees two instances of a node with the same
   * build target.
   */
  @Test
  public void testRdepsWithSymlinks() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // We can't have symlinks checked into the Buck repo, so we have to create the one we're using
    // for the test below here.
    workspace.move("symlinks/a/BUCK.disabled", "symlinks/a/BUCK");
    CreateSymlinksForTests.createSymLink(
        workspace.resolve("symlinks/a/inputs"),
        workspace.getDestPath().getFileSystem().getPath("real_inputs"));
    workspace.runBuckCommand("query", "rdeps(//symlinks/..., //symlinks/a:a)");
  }

  @Parameters(method = "getJsonParams")
  @Test
  public void testMultipleGetTestsofDirectReverseDependenciesJSON(String jsonParam)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            jsonParam,
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

  @Parameters(method = "getJsonParams")
  @Test
  public void testGetMultipleSrcsAttribute(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", jsonParam, "labels('srcs', '%s')", "//example:");
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
    assertLinesMatch("stdout-filter-four", result, workspace);
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

  @Parameters(method = "getDotParams")
  @Test
  public void testAllPathsDepsOneToFiveSix(String dotParams) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            dotParams,
            "allpaths(deps(//example:one, 1), set(//example:five //example:six))");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-allpaths-deps-one-to-five-six.dot"))));
  }

  @Parameters(method = "getDotParams")
  @Test
  public void testAllPathsDepsOneToFiveSixFormatSet(String dotParams) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            dotParams,
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

  @Parameters(method = "getDotParams")
  @Test
  public void testDotOutputForDeps(String dotParams) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("query", dotParams, "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-deps-one.dot"))));

    result = workspace.runBuckCommand("query", "--output-format", "dot_bfs", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-bfs-deps-one.dot"))));
  }

  @Parameters(method = "getDotParams")
  @Test
  public void testDotOutputWithAttributes(String dotParams) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", dotParams, "deps(//example:one)", "--output-attributes", "name", "buck.type");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                workspace.getFileContents("stdout-deps-one-with-attributes.dot"))));
  }

  @Parameters(method = "getSortOutputParams")
  @Test
  public void testRankOutputForDeps(String sortOutputParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", sortOutputParam, "minrank", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-minrank-deps-one"))));

    result = workspace.runBuckCommand("query", sortOutputParam, "maxrank", "deps(//example:one)");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(equalToIgnoringPlatformNewlines(workspace.getFileContents("stdout-maxrank-deps-one"))));
  }

  @Parameters(method = "getSortOutputParams")
  @Test
  public void testRankOutputWithAttributes(String sortOutputParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "deps(//example:one)",
            sortOutputParam,
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
            "deps(//example:one)",
            sortOutputParam,
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

  @Parameters(method = "getSortOutputParams")
  @Test
  public void testRankOutputWithAttributesIgnoresFlavors(String sortOutputParam)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "deps(//example:one#doc)",
            sortOutputParam,
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
            sortOutputParam,
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
  public void testRegexFilterAttrTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "attrregexfilter(tests, '.*-tests', '//example/...')");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "//example/app:seven\n//example:four\n//example:one\n//example:six\n")));

    result =
        workspace.runBuckCommand(
            "query", "attrregexfilter(tests, '(three|four)-tests', '//example/...')");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//example:four\n")));
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

  @Parameters(method = "getJsonParams")
  @Test
  public void testBuildFileFunctionJson(String jsonParam) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            jsonParam,
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
    assertEquals(
        normalizeContents(
            String.format(
                "%s%n%s%n",
                MorePaths.pathWithPlatformSeparators("example/4-test.txt"),
                MorePaths.pathWithPlatformSeparators("example/1.txt"))),
        normalizeContents(result.getStdout()));
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
        containsInAnyOrder(
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
        containsInAnyOrder("//owners_violating_package_boundary/inner:lib"));
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
        new JsonMatcher(
            "{"
                + "  \"//:genrule_with_select_in_srcs\" : {"
                + "    \"srcs\" : [ \":c\", \":a\" ]"
                + "  }"
                + "}"));
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
  public void testBooleanConfigurableValuesWorkInOutputAttributes() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:genrule_with_boolean",
            "--output-attributes",
            "executable");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:genrule_with_boolean\" : {\n"
                    + "    \"executable\" : false\n"
                    + "  }\n"
                    + "}\n")));

    result =
        workspace.runBuckCommand(
            "query", "//:genrule_with_boolean", "--output-attributes", "executable");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(
            equalToIgnoringPlatformNewlines(
                "{\n"
                    + "  \"//:genrule_with_boolean\" : {\n"
                    + "    \"executable\" : true\n"
                    + "  }\n"
                    + "}\n")));
  }

  @Test
  public void testBooleanConfigurableValuesThrowWhenConcatenated() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "-c",
            "config.mode=a",
            "//:genrule_with_concatenated_boolean",
            "--output-attributes",
            "executable");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsIgnoringPlatformNewlines(
            "type 'java.lang.Boolean' doesn't support select concatenation"));

    result =
        workspace.runBuckCommand(
            "query", "//:genrule_with_concatenated_boolean", "--output-attributes", "executable");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsIgnoringPlatformNewlines(
            "type 'java.lang.Boolean' doesn't support select concatenation"));
  }

  @Test
  public void testExcludeIncompatibleTargetsFiltersTargetsByConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_incompatible_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "kind(genrule, //:)", "--target-platforms", "//:linux_platform");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//:a\n")));

    result =
        workspace.runBuckCommand(
            "query", "kind(genrule, //:)", "--target-platforms", "//:osx_platform");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//:b\n")));
  }

  @Test
  public void ownerShouldExcludeIncompatibleTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_incompatible_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "owner(lib/A.java)", "--target-platforms", "//:linux_platform");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalToIgnoringPlatformNewlines("//lib:libA\n")));
  }

  @Test
  public void ownerShouldNotIncludeIncompatibleTargetsWhenRequested() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_incompatible_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "owner('A.java')", "--target-platforms", "//:linux_platform");

    result.assertSuccess();
    assertEquals("//:lib_linux", result.getStdout().trim());
  }

  @Test
  public void testOutputFileParameter() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    Path outputFile = tmp.newFile();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand(
            "query",
            "--output-format",
            "json",
            "--output-file",
            outputFile.toString(),
            "testsof(deps(%s))",
            "//example:one",
            "//example:two",
            "//example:three",
            "//example:four",
            "//example:five",
            "//example:six");
    result.assertSuccess();

    String outputFileContent = new String(Files.readAllBytes(outputFile));

    assertThat(
        parseJSON(outputFileContent),
        is(equalTo(parseJSON(workspace.getFileContents("stdout-all-deps-tests-map.json")))));
  }

  @Test
  public void testThriftOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("query", "deps(//example:one)", "--output-format", "thrift");
    result.assertSuccess();

    String stdout = result.getStdout();
    DirectedAcyclicGraph thriftDag = parseThrift(stdout.getBytes(StandardCharsets.UTF_8));
    assertEquals(6, thriftDag.getNodesSize());
    assertThat(
        nodesToStringList(thriftDag.getNodes()),
        containsInAnyOrder(
            "//example:one",
            "//example:two",
            "//example:three",
            "//example:four",
            "//example:five",
            "//example:six"));

    assertEquals(8, thriftDag.getEdgesSize());
    assertThat(
        edgesToStringList(thriftDag.getEdges()),
        containsInAnyOrder(
            "//example:one->//example:two",
            "//example:one->//example:three",
            "//example:two->//example:four",
            "//example:three->//example:five",
            "//example:three->//example:four",
            "//example:three->//example:six",
            "//example:four->//example:six",
            "//example:five->//example:six"));
  }

  private static JsonNode parseJSON(String content) throws IOException {
    JsonNode original = ObjectMappers.READER.readTree(ObjectMappers.createParser(content));
    return normalizeJson(original);
  }

  /** Normalizes the JSON by sorting all of the arrays of strings. */
  private static JsonNode normalizeJson(JsonNode node) {
    if (node.isValueNode()) {
      return node;
    } else if (node.isArray()) {
      // Only if this is an array of strings, copy the array and sort it.
      if (Iterables.all(node, (JsonNode child) -> child.isTextual())) {
        ArrayList<String> values = new ArrayList<>();
        Iterables.addAll(values, Iterables.transform(node, (JsonNode child) -> child.asText()));
        values.sort(String::compareTo);
        ArrayNode normalized = (ArrayNode) ObjectMappers.READER.createArrayNode();
        values.forEach((String value) -> normalized.add(value));
        return normalized;
      } else {
        return node;
      }
    } else if (node.isObject()) {
      ObjectNode original = (ObjectNode) node;
      ObjectNode normalized = (ObjectNode) ObjectMappers.READER.createObjectNode();
      for (Iterator<Map.Entry<String, JsonNode>> iter = original.fields(); iter.hasNext(); ) {
        Map.Entry<String, JsonNode> entry = iter.next();
        normalized.set(entry.getKey(), normalizeJson(entry.getValue()));
      }
      return normalized;
    } else {
      throw new IllegalArgumentException("Unexpected node type: " + node);
    }
  }

  private static DirectedAcyclicGraph parseThrift(byte[] bytes) throws IOException {
    DirectedAcyclicGraph thriftDag = new DirectedAcyclicGraph();
    ThriftUtil.deserialize(ThriftProtocol.BINARY, bytes, thriftDag);
    return thriftDag;
  }

  Object getJsonParams() {
    return new Object[] {"--json", "--output-format=json"};
  }

  Object getDotParams() {
    return new Object[] {"--dot", "--output-format=dot"};
  }

  Object getSortOutputParams() {
    return new Object[] {"--sort-output", "--output"};
  }

  @Test
  public void testDependencyCycles() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("query", "deps(//cycles:a)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:b)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:c)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:d)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:e)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(set(//cycles:f //cycles/dir:g))");
    assertContainsCycle(
        processResult,
        ImmutableList.of("//cycles:f", "//cycles/dir:g", "//cycles:h", "//cycles/dir:i"));
  }

  /**
   * Assert that the command failed and that the stderr message complains about a cycle with links
   * in the order specified by {@code chain}.
   */
  private static void assertContainsCycle(ProcessResult processResult, List<String> chain) {
    // Should have failed because graph contains a cycle.
    processResult.assertFailure();

    String stderr = processResult.getStderr();
    List<String> cycleCandidates = new ArrayList<>(chain.size());
    int chainSize = chain.size();
    Joiner joiner = Joiner.on(" -> ");
    for (int i = 0; i < chainSize; i++) {
      List<String> elements = new ArrayList<>(chain.size() + 1);
      for (int j = 0; j < chainSize; j++) {
        int index = (i + j) % chainSize;
        elements.add(chain.get(index));
      }
      elements.add(chain.get(i));
      String cycle = joiner.join(elements);
      if (stderr.contains(cycle)) {
        // Expected cycle string found!
        return;
      }
      cycleCandidates.add(cycle);
    }
    fail(stderr + " contained none of " + Joiner.on('\n').join(cycleCandidates));
  }

  @Test
  public void dependencyStackInConfigurationsInRdeps() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command_configurations", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("query", "rdeps(set(//:b), set(//:c))");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsIgnoringPlatformNewlines(
            "Cannot use select() expression when target platform is not specified\n"
                + "    At //:red\n"
                + "    At //:a\n"
                + "    At //:b"));
  }

  @Test
  public void queryOutputsConfigurationDefaultTargetPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_outputs_configuration", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("query", "//:j", "--output-attributes", "buck.*");
    result.assertSuccess();

    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("j-p-query-out.json")))));
  }

  @Test
  public void queryOutputsConfigurationCommandLine() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_outputs_configuration", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "--target-platforms=//:q", "//:j", "--output-attributes", "buck.*");
    result.assertSuccess();

    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("j-q-query-out.json")))));
  }

  @Test
  public void queryOutputsComputedAttributes() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "query", "//example:three", "--output-attributes", "name", "visibility");
    result.assertSuccess();

    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("example/three-query-out.json")))));
  }
}
