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

package com.facebook.buck.cli.cquery;

import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.ThriftOutputUtils;
import com.facebook.buck.query.thrift.DirectedAcyclicGraph;
import com.facebook.buck.query.thrift.DirectedAcyclicGraphNode;
import com.facebook.buck.testutil.OutputHelper;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class ConfiguredQueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are the same as those in
   * the specified file. Note that sort order is not guaranteed by {@code buck query} unless it is
   * specified explicitly via {@code --sort-output}.
   */
  private void assertOutputMatchesFileContents(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    // All lines in expected output files are sorted so sort the output from `buck query` before
    // comparing. Although query/--sort-output claims to sort labels by default, this does not
    // appear to be honored, in practice.
    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        OutputHelper.normalizeOutputLines(normalizeNewlines(result.getStdout())));
  }

  /** Same as {@link #assertOutputMatchesFileContents} but doesn't attempt to sort output */
  private void assertOutputMatchesFileContentsExactly(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        normalizeNewlines(result.getStdout()));
  }

  private void assertJSONOutputMatchesFileContents(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    assertEquals(
        OutputHelper.parseJSON(workspace.getFileContents(expectedOutputFile)),
        OutputHelper.parseJSON(result.getStdout()));
  }

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are identical to {@code
   * sortedExpectedOutput}. The stdout of {@code result} is sorted by line before being compared to
   * {@code sortedExpectedOutput} to ensure deterministic results.
   */
  private void assertOutputMatches(String sortedExpectedOutput, ProcessResult result) {
    result.assertSuccess();

    assertEquals(
        sortedExpectedOutput,
        OutputHelper.normalizeOutputLines(normalizeNewlines(result.getStdout())).trim());
  }

  @Test
  public void basicTargetPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("cquery", "//lib:foo");
    // TODO(srice): We shouldn't expect it to print a readable name, but until we know what the hash
    // is going to be it doesn't matter what we put here.
    assertOutputMatches("//lib:foo (//config/platform:ios)", result);
  }

  @Test
  public void basicJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "//lib/...", "--target-universe", "//bin:mac-bin", "--output-format", "json");
    assertJSONOutputMatchesFileContents("stdout-basic-json-printing.json", result, workspace);
  }

  @Test
  public void basicAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:mac-bin",
            "--output-attribute",
            "buck.type",
            "--output-attribute",
            "srcs");
    assertJSONOutputMatchesFileContents("stdout-basic-attribute-printing.json", result, workspace);
  }

  @Test
  public void basicDotPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "dot");
    assertOutputMatchesFileContentsExactly("stdout-basic-dot-printing", result, workspace);
  }

  @Test
  public void basicDotAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "dot",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-attribute-printing", result, workspace);
  }

  @Test
  public void basicDotCompactPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "dot_compact");
    assertOutputMatchesFileContentsExactly("stdout-basic-dot-compact-printing", result, workspace);
  }

  @Test
  public void basicDotCompactAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "dot_compact",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-compact-attribute-printing", result, workspace);
  }

  @Test
  public void basicDotBfsPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:mac-bin",
            "--output-format",
            "dot_bfs");
    assertOutputMatchesFileContentsExactly("stdout-basic-dot-bfs-printing", result, workspace);
  }

  @Test
  public void basicDotBfsAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:mac-bin",
            "--output-format",
            "dot_bfs",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-attribute-printing", result, workspace);
  }

  @Test
  public void basicDotBfsCompactPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:mac-bin",
            "--output-format",
            "dot_bfs_compact");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-compact-printing", result, workspace);
  }

  @Test
  public void basicDotBfsCompactAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:mac-bin",
            "--output-format",
            "dot_bfs_compact",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-compact-attribute-printing", result, workspace);
  }

  @Test
  public void basicThriftPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "thrift");

    result.assertSuccess();
    DirectedAcyclicGraph thriftDag = ThriftOutputUtils.parseThriftDag(result.getStdout());
    assertEquals(
        ImmutableSet.copyOf(ThriftOutputUtils.nodesToStringList(thriftDag)),
        ImmutableSet.of(
            "//lib:bar (//config/platform:ios)",
            "//lib:bar (//config/platform:macos)",
            "//lib:foo (//config/platform:ios)",
            "//lib:foo (//config/platform:macos)",
            "//lib:maconly (//config/platform:macos)"));
    assertEquals(
        ImmutableSet.copyOf(ThriftOutputUtils.edgesToStringList(thriftDag)),
        ImmutableSet.of(
            "//lib:foo (//config/platform:ios)->//lib:bar (//config/platform:ios)",
            "//lib:foo (//config/platform:macos)->//lib:bar (//config/platform:macos)",
            "//lib:foo (//config/platform:macos)->//lib:maconly (//config/platform:macos)"));
  }

  @Test
  public void basicThriftAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib/...",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "thrift",
            "--output-attribute",
            "srcs");

    result.assertSuccess();
    DirectedAcyclicGraph thriftDag = ThriftOutputUtils.parseThriftDag(result.getStdout());
    // Since this is the same query as `basicThriftPrinting` (plus the additional CLI param) we're
    // relying on the previous test to validate the structure of the output.
    DirectedAcyclicGraphNode node =
        ThriftOutputUtils.findNodeByName(thriftDag, "//lib:foo (//config/platform:macos)").get();
    Map<String, String> attributes = node.getNodeAttributes();
    assertEquals(1, attributes.size());
    assertEquals("[foo-macos.m]", attributes.get("srcs"));
  }

  @Test
  public void basicMultiQueryPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "%s",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "//lib:foo",
            "//lib:maconly");
    assertOutputMatchesFileContents("stdout-basic-multi-query-printing", result, workspace);
  }

  @Test
  public void basicMultiQueryJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "%s",
            "--target-universe",
            "//bin:ios-bin,//bin:mac-bin",
            "--output-format",
            "json",
            "//lib:foo",
            "//lib:maconly");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-multi-query-json-printing.json", result, workspace);
  }

  @Test
  public void basicMultiQueryAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "%s",
            "--target-universe",
            "//bin:mac-bin",
            "--output-attribute",
            "buck.type",
            "--output-attribute",
            "srcs",
            "//bin/...",
            "//lib/...");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-multi-query-attribute-printing.json", result, workspace);
  }

  @Test
  public void configFunctionConfiguresTargetForSpecificPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("cquery", "config(//lib:foo, //config/platform:tvos)");
    assertOutputMatches("//lib:foo (//config/platform:tvos)", result);
  }

  @Test
  public void implicitTargetUniverseForRdeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    // Even though `//lib:bar` has a default_target_platform of tvos, the universe is created with
    // ios-bin and therefore we match the version of bar that is configured for ios.
    ProcessResult result = workspace.runBuckCommand("cquery", "rdeps(//bin:ios-bin, //lib:bar, 0)");
    assertOutputMatches("//lib:bar (//config/platform:ios)", result);
  }

  @Test
  public void configFunctionConfiguresTargetForDefaultTargetPlatformIfNoSecondArgumentGiven()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "config(//lib:bar)", "--target-universe", "//bin:ios-bin,//bin:tvos-bin");
    assertOutputMatches("//lib:bar (//config/platform:tvos)", result);
  }

  @Test
  public void targetUniverseChangesOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult tvOSResult =
        workspace.runBuckCommand("cquery", "//lib:foo", "--target-universe", "//bin:tvos-bin");
    assertOutputMatches("//lib:foo (//config/platform:tvos)", tvOSResult);

    ProcessResult macOSResult =
        workspace.runBuckCommand("cquery", "//lib:foo", "--target-universe", "//bin:mac-bin");
    assertOutputMatches("//lib:foo (//config/platform:macos)", macOSResult);
  }

  @Test
  public void ownerForFileWithOwnerThatsOutsideTargetUniverseReturnsNothing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    // Even though `lib/maconly.m` is unconditionally included as a source of `//lib:maconly`, that
    // target is outside the target universe and therefore the query should return no results.
    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "owner(lib/maconly.m)", "--target-universe", "//bin:tvos-bin");
    assertOutputMatches("", result);
  }

  @Test
  public void multipleLinesPrintedForOneTargetInMulitpleConfigurations() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "config(//lib:foo, //config/platform:ios) + config(//lib:foo, //config/platform:macos)");
    assertOutputMatchesFileContents(
        "stdout-multiple-lines-printed-for-one-target-in-multiple-configurations",
        result,
        workspace);
  }

  @Test
  public void
      twoTargetsWithDifferentConfigurationsInTargetUniverseBothGetPrintedWithRecursiveTargetSpec()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "set(//lib/...)", "--target-universe", "//bin:ios-bin,//bin:tvos-bin");
    assertOutputMatchesFileContents(
        "stdout-two-targets-in-target-universe-causes-overlap-to-be-printed-in-both-configurations",
        result,
        workspace);
  }

  @Test
  public void
      twoTargetsWithDifferentConfigurationsInTargetUniverseBothGetPrintedWithSpecificTargetSpec()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "set(//lib:foo)", "--target-universe", "//bin:ios-bin,//bin:tvos-bin");
    assertOutputMatches(
        "//lib:foo (//config/platform:ios)\n//lib:foo (//config/platform:tvos)", result);
  }

  @Test
  public void targetPlatformsArgCausesUniverseToBeCreatedWithThatPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "//lib:foo",
            "--target-universe",
            "//bin:tvos-bin",
            "--target-platforms",
            "//config/platform:ios");
    assertOutputMatches("//lib:foo (//config/platform:ios)", result);
  }

  @Test
  public void configFunctionCanCreateTargetsOtherThanTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "config(//lib:foo, //config/platform:macos)",
            "--target-universe",
            "//bin:tvos-bin",
            "--target-platforms",
            "//config/platform:ios");
    assertOutputMatches("//lib:foo (//config/platform:macos)", result);
  }
}
