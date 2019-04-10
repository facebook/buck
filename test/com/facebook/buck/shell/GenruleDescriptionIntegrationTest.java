/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GenruleDescriptionIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
                this, "genrule_classpath_filtering", temporaryFolder)
            .setUp();
  }

  @Test
  public void classpathMacro() throws Exception {
    expectGenruleOutput(
        ":echo_all",
        ImmutableList.of("//:lib_a", "//:lib_b", "//:lib_d", "//annotations:proc-lib"));
  }

  @Test
  public void depsFromClasspathMacroAreFilteredByDep() throws Exception {
    expectGenruleOutput(":echo_with_ap_dep_is_proc", ImmutableList.of("//:lib_b"));
  }

  @Test
  public void depsFromClasspathMacroAreFilteredByAnnotationProcessor() throws Exception {
    expectGenruleOutput(":echo_with_annotation_processor_is_proc", ImmutableList.of("//:lib_b"));
  }

  @Test
  public void depsFromClasspathMacroAreFilteredByPlugin() throws Exception {
    expectGenruleOutput(":echo_with_plugin_is_proc", ImmutableList.of("//:lib_d"));
  }

  @Test
  public void classpathMacroDepthLimited() throws Exception {
    expectGenruleOutput(":echo_classpath_0", ImmutableList.of("//:lib_a"));
    expectGenruleOutput(
        ":echo_classpath_1",
        ImmutableList.of("//:lib_a", "//:lib_b", "//:lib_d", "//annotations:proc-lib"));
  }

  @Test
  public void depsFromSetAreFilteredByKind() throws Exception {
    expectGenruleOutput(":echo_with_kind_is_binary", ImmutableList.of("//:app"));
    expectGenruleOutput(":echo_with_kind_is_library", ImmutableList.of("//:lib_a", "//:lib_b"));
  }

  @Test
  public void depsFromDepsQueryToFile() throws Exception {
    expectGenruleOutput(
        ":echo_with_deps_to_file",
        ImmutableList.of(
            "//:app",
            "//:lib_a",
            "//:lib_b",
            "//:lib_d",
            "//annotations:proc",
            "//annotations:proc-lib"));
  }

  @Test
  public void depsFromDepsQuery() throws Exception {
    expectGenruleOutput(
        ":echo_with_deps",
        ImmutableList.of(
            "//:app",
            "//:lib_a",
            "//:lib_b",
            "//:lib_d",
            "//annotations:proc",
            "//annotations:proc-lib"));
  }

  @Test
  public void depsFromDepsQueryWithDep1() throws Exception {
    expectOutputPathsGenruleOutput(":echo_with_deps_1", ImmutableList.of(":app", ":lib_a"));
  }

  @Test
  public void depsFromGenruleTransitive() throws Exception {
    expectGenruleOutput(
        ":echo_with_genrule_deps",
        ImmutableList.of(
            "//:echo_with_annotation_processor_is_proc",
            "//:lib_a",
            "//:lib_b",
            "//:lib_d",
            "//annotations:proc",
            "//annotations:proc-lib"));
  }

  @Test
  public void filteredFromSet() throws Exception {
    expectOutputPathsGenruleOutput(
        ":echo_from_filtered_set",
        ImmutableList.of("//annotations:proc-lib", "//:app", "//:lib_a"));
  }

  @Test
  public void testRdepsQuery() throws Exception {
    expectGenruleOutput(":echo_with_rdeps", ImmutableList.of("//:app", "//:lib_a", "//:lib_d"));
  }

  @Test
  public void testRdepsQueryWithDepth1() throws Exception {
    expectGenruleOutput(":echo_with_rdeps_1", ImmutableList.of("//:lib_a", "//:lib_d"));
  }

  @Test
  public void testQueryResultsAreInvalidatedWhenDirectDepChanges() throws Exception {
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");

    // Now, build again, assert we get the previous result
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertNotTargetBuiltLocally("//:echo_deps_of_a");

    // Now, add a dep to lib_a, which is the only explictly mentioned target and
    // assert the query is run
    workspace.replaceFileContents("BUCK", "#add_import_to_lib_a", "");
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();

    // Query targets should NOT build dependencies
    workspace.getBuildLog().assertTargetIsAbsent("//:lib_a");
    // But should still be invalidated by their changes
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");
  }

  @Test
  public void testQueryResultsAreInvalidatedWhenTransitiveDepChanges() throws Exception {
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");

    // Now, build again, assert we get the previous result
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertNotTargetBuiltLocally("//:echo_deps_of_a");

    // Now, edit lib_b, which is NOT mentioned in the genrule, but IS a transitive dependency,
    // and assert the query is re-run
    workspace.replaceFileContents("BUCK", "#add_import_to_lib_b", "");
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();

    // Query targets should NOT build dependencies
    workspace.getBuildLog().assertTargetIsAbsent("//:lib_b");
    // But should still be invalidated by their changes
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");
  }

  @Test
  public void testQueryResultsReflectTransitiveChanges() throws Exception {
    Assume.assumeFalse(Platform.detect().equals(Platform.WINDOWS));
    // Assert the query result starts empty
    expectGenruleOutput(":echo_with_has_debug_flag", ImmutableList.of());

    // Now edit the annotation processor target and add the flag
    workspace.replaceFileContents("annotations/BUCK", "#placeholder", "extra_arguments=['-g']");

    // Assert that the query output updates
    expectGenruleOutput(":echo_with_has_debug_flag", ImmutableList.of("//annotations:proc-lib"));
  }

  @Test
  public void testQueryCanBeTheOnlyThingReferencingAFile() throws Exception {
    // Assert the query can find the target even though it's the only reference to it
    expectGenruleOutput(":ensure_parsing_if_this_is_only_ref", ImmutableList.of("//other:other"));
  }

  @Test
  public void testGettingLabelsInQueryCanFindTargetDep() throws Exception {
    expectGenruleOutput(":echo_labels_of", ImmutableList.of("//other:hidden"));
  }

  @Test
  public void testQueryOutputsCanGetOutputFromLabel() throws Exception {
    expectGenruleOutput(
        ":echo_labels_of_output", ImmutableList.of(getOutputFile("//other:hidden").toString()));
  }

  @Test
  public void testQueryTargetsAndOutputsCanGetOutputFromLabel() throws Exception {
    expectGenruleOutput(
        ":echo_labels_of_targets_and_output",
        ImmutableList.of("//other:hidden", getOutputFile("//other:hidden").toString()));
  }

  @Test
  public void testQueryTargetsAndOutputsWithLabels() throws Exception {
    expectGenruleOutput(
        ":package_genrule",
        ImmutableList.of(
            getOutputFile("//:resources_a").toString(),
            getOutputFile("//:resources_b").toString()));
  }

  @Test
  public void classpathMacroOnBinary() throws Exception {
    expectGenruleOutput(
        ":echo_classpath_binary",
        ImmutableList.of("//:app", "//:lib_a", "//:lib_b", "//:lib_d", "//annotations:proc-lib"));
  }

  private void expectOutputPathsGenruleOutput(String genrule, List<String> expectedOutputs)
      throws Exception {
    expectGenruleOutput(
        genrule,
        expectedOutputs.stream()
            .map(this::getOutputFile)
            .map(Path::toString)
            .collect(Collectors.toList()));
  }

  private void expectGenruleOutput(String genrule, List<String> expectedOutputs) throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", genrule);
    buildResult.assertSuccess();

    String outputFileContents = workspace.getFileContents(getOutputFile(genrule)).trim();
    List<String> actualOutput =
        Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().splitToList(outputFileContents);
    assertEquals(expectedOutputs, actualOutput);
  }

  private Path getOutputFile(String targetName) {
    try {
      ProcessResult buildResult =
          workspace.runBuckCommand("targets", targetName, "--show-full-output", "--json");
      buildResult.assertSuccess();
      JsonNode jsonNode = ObjectMappers.READER.readTree(buildResult.getStdout()).get(0);
      assert jsonNode.has("buck.outputPath");
      return Paths.get(jsonNode.get("buck.outputPath").asText());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
