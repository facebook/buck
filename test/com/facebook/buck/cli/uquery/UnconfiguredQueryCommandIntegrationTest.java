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

package com.facebook.buck.cli.uquery;

import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertJSONOutputMatchesFileContents;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatches;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesExactly;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesFileContents;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesFileContentsExactly;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesPaths;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.ThriftOutputUtils;
import com.facebook.buck.query.thrift.DirectedAcyclicGraph;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class UnconfiguredQueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * =============================================================================================
   * ====================================== Output Formats =======================================
   * =============================================================================================
   */
  @Test
  public void basicTargetPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "//lib:foo");
    assertOutputMatches("//lib:foo", result);
  }

  @Test
  public void basicJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "//lib:", "--output-format", "json");
    assertJSONOutputMatchesFileContents("stdout-basic-json-printing.json", result, workspace);
  }

  @Test
  public void basicJsonAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "//lib:", "--output-format", "json", "--output-attribute", "srcs");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-json-attribute-printing.json", result, workspace);
  }

  @Test
  public void basicDotPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("uquery", "deps(//lib/...)", "--output-format", "dot");
    assertOutputMatchesFileContents("stdout-basic-dot-printing", result, workspace);
  }

  @Test
  public void basicDotAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery",
            "deps(//lib/...) - set(//lib:devtools)",
            "--output-format",
            "dot",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContents("stdout-basic-dot-attribute-printing", result, workspace);
  }

  @Test
  public void basicDotCompactPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    // `dot_compact` poses a unique problem for testing because it assigns nodes an integer id in
    // a nondeterministic fashion (well, only nondeterministic when you use the UNDEFINED) output
    // order, which we do by default. Therefore the only way to get a test that's determinstic is
    // to make sure the output only has one node.
    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "deps(//lib/...) ^ set(//lib:devtools)", "--output-format", "dot_compact");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-compact-printing.dot", result, workspace);
  }

  @Test
  public void basicDotCompactAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery",
            "deps(//lib/...) ^ set(//lib:bar)",
            "--output-format",
            "dot_compact",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-compact-attribute-printing.dot", result, workspace);
  }

  @Test
  public void basicDotBfsPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("uquery", "deps(//bin:foo-bin)", "--output-format", "dot_bfs");
    assertOutputMatchesFileContentsExactly("stdout-basic-dot-bfs-printing.dot", result, workspace);
  }

  @Test
  public void basicDotBfsAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery",
            "deps(//bin:foo-bin) - set(//lib:devtools)",
            "--output-format",
            "dot_bfs",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-attribute-printing.dot", result, workspace);
  }

  @Test
  public void basicDotBfsCompactPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "deps(//bin:foo-bin)", "--output-format", "dot_bfs_compact");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-compact-printing.dot", result, workspace);
  }

  @Test
  public void basicDotBfsCompactAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery",
            "deps(//bin:foo-bin) - set(//lib:devtools)",
            "--output-format",
            "dot_bfs_compact",
            "--output-attribute",
            "srcs");
    assertOutputMatchesFileContentsExactly(
        "stdout-basic-dot-bfs-compact-attribute-printing.dot", result, workspace);
  }

  @Test
  public void basicThriftPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "deps(//lib/...) ^ set(//lib:)", "--output-format", "thrift");

    result.assertSuccess();
    DirectedAcyclicGraph thriftDag = ThriftOutputUtils.parseThriftDag(result.getStdout());
    assertEquals(
        ImmutableSet.copyOf(ThriftOutputUtils.nodesToStringList(thriftDag)),
        ImmutableSet.of("//lib:bar", "//lib:foo", "//lib:devtools"));
    assertEquals(
        ImmutableSet.copyOf(ThriftOutputUtils.edgesToStringList(thriftDag)),
        ImmutableSet.of(
            "//lib:foo->//lib:bar", "//lib:foo->//lib:devtools", "//lib:bar->//lib:devtools"));
  }

  @Test
  public void basicMultiQueryPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "%s", "//bin:", "//config/platform:");
    assertOutputMatchesFileContents("stdout-basic-multi-query-printing", result, workspace);
  }

  @Test
  public void basicMultiQueryJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "%s", "//lib:", "//config/platform:", "--output-format", "json");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-multi-query-json-printing.json", result, workspace);
  }

  @Test
  public void basicMultiQueryJsonAttributePrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery",
            "%s",
            "//bin:",
            "//lib:",
            "--output-format",
            "json",
            "--output-attribute",
            "compatible_with");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-multi-query-json-attribute-printing.json", result, workspace);
  }

  /**
   * =============================================================================================
   * =============================== General uquery functionality ================================
   * =============================================================================================
   */
  @Test
  public void doesntConfigureDependenciesOfTargetForPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    // Since `foo-bin` is set up for java8 running this on a configured graph would give no results,
    // since DevtoolsEleven is only used on java11 platforms.
    ProcessResult result =
        workspace.runBuckCommand("uquery", "deps(//bin:foo-bin) ^ owner(lib/DevtoolsEleven.java)");
    assertOutputMatches("//lib:devtools", result);
  }

  @Test
  public void targetPlatformsArgDoesntChangeOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    String query = "inputs(//lib:devtools)";
    String expected = "lib/DevtoolsEight.java\nlib/DevtoolsEleven.java";

    ProcessResult resultForNoArg = workspace.runBuckCommand("uquery", query);
    assertOutputMatches(expected, resultForNoArg);

    ProcessResult resultForSpecificPlatform =
        workspace.runBuckCommand(
            "uquery", query, "--target-platforms", "//config/platform:java11-dev");
    assertOutputMatches(expected, resultForSpecificPlatform);
  }

  @Test
  public void doesntTreatTestAttributeAsParseDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "large_project", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("uquery", "kind('.*_test', deps(//libraries/...))");
    assertOutputMatchesExactly("", result);
  }

  @Test
  public void treatsPlatformRulesAsUnionOfAllPossibilities() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_rules", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "inputs(//:foo)");
    assertOutputMatches("foo-android.c\nfoo-iphone.c", result);
  }

  @Test
  public void considersRawPathsAsInputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "path_traversal", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "inputs(//:foo)");
    assertOutputMatchesPaths("res", result);
  }

  /**
   * =============================================================================================
   * ================================== Function specific tests ==================================
   * =============================================================================================
   */
  @Test
  public void attrfilterFunctionOnlyReturnsTargetsWithMatchingValue() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "large_project", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "attrfilter(compiler_flags, '-Oz', //libraries/apple/...)");

    assertOutputMatchesFileContents(
        "stdout-attrfilter-function-only-returns-targets-with-matching-value", result, workspace);
  }

  @Test
  public void attrregexfilterFunctionAppliesRegexMatchingToAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "large_project", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "attrregexfilter(default_target_platform, '.*-opt', //apps/apple/...)");

    assertOutputMatchesFileContents(
        "stdout-attrregexfilter-function-applies-regex-matching-to-attribute", result, workspace);
  }

  @Test
  public void buildfileFunctionGivesPathToBUCKFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "large_project", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "buildfile(appletv-app-prod)");
    assertOutputMatchesPaths("apps/apple/BUCK", result);
  }

  @Test
  public void depsFunctionPrintsDependenciesOfTargetInAnyConfiguration() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "deps(//bin:foo-bin)");
    assertOutputMatchesFileContents(
        "stdout-deps-function-prints-dependencies-of-target-in-any-configuration",
        result,
        workspace);
  }

  @Test
  public void inputsFunctionPrintsAllFilesUsedByATarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "inputs(deps(//bin:bar-bin))");
    assertOutputMatchesFileContents(
        "stdout-inputs-function-prints-all-files-used-by-a-target", result, workspace);
  }

  @Test
  public void inputsFunctionPrintsImplicitInputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implicit_inputs", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "inputs(//:foo.txt)");
    assertOutputMatches("foo.txt", result);
  }

  @Test
  public void kindFunctionOnlyPrintsTargetsOfSpecificType() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "kind(keystore, //bin:)");
    assertOutputMatches("//bin:keystore-debug\n//bin:keystore-prod", result);
  }

  @Test
  public void labelsFunctionPrintsTargetsFromSpecificAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "labels(keystore, //bin:foo-bin)");
    assertOutputMatchesFileContents(
        "stdout-labels-function-prints-targets-from-specific-attribute", result, workspace);
  }

  @Test
  public void labelsFunctionCanPrintFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("uquery", "labels(properties, //bin:keystore-prod)");
    assertOutputMatchesExactly("bin/prod.keystore.properties\n", result);
  }

  @Test
  public void ownerFunctionPrintsTargetsWithGivenFileInSrcs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "owner(lib/DevtoolsEight.java)");
    assertOutputMatches("//lib:devtools", result);
  }

  @Test
  public void ownerFunctionPrintsTargetsThatOwnFileViaImplicitInputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implicit_inputs", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "owner(foo.txt)");
    assertOutputMatches("//:foo.txt\n//:target-that-references-foo-txt-explicitly", result);
  }

  @Test
  public void testsofFunctionPrintsValueOfTestsAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "large_project", tmp);
    workspace.setUp();

    // We're being a bit tricky here. On a configured graph this intersection would return nothing,
    // since `keystore-prod` is only used in configurations where `devtools` isn't used. We're
    // not operating on the configured graph though.
    ProcessResult result = workspace.runBuckCommand("uquery", "testsof(//libraries/...)");
    assertOutputMatchesFileContents(
        "stdout-testsof-function-prints-value-of-tests-attribute", result, workspace);
  }

  @Test
  public void rdepsFunctionPrintsNodesWithIncomingEdgesToTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    // We're being a bit tricky here. On a configured graph this intersection would return nothing,
    // since `keystore-prod` is only used in configurations where `devtools` isn't used. We're
    // not operating on the configured graph though.
    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "rdeps(//bin:, //bin:keystore-prod) ^ rdeps(//bin:, //lib:devtools)");
    assertOutputMatches("//bin:bar-bin\n//bin:foo-bin", result);
  }
}
