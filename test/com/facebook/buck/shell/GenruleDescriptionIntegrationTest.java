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
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GenruleDescriptionIntegrationTest {
  private final ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {

    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_classpath_filtering", temporaryFolder).setUp();
  }

  @Test
  public void classpathMacro() throws Exception {
    expectGenruleOutput(
        ":echo_all",
        ImmutableList.of(
            "//:lib_a",
            "//:lib_b",
            "//annotations:proc"));
  }

  @Test
  public void depsFromClasspathMacroAreFilteredByDep() throws Exception {
    expectGenruleOutput(
        ":echo_with_dep_is_proc",
        ImmutableList.of(
            "//:lib_a"));
  }

  @Test
  public void depsFromClasspathMacroAreFilteredByAnnotationProcessor() throws Exception {
    expectGenruleOutput(
        ":echo_with_annotation_processor_is_proc",
        ImmutableList.of(
            "//:lib_b"));
  }

  @Test
  public void depsFromSetAreFilteredByKind() throws Exception {
    expectGenruleOutput(
        ":echo_with_kind_is_binary",
        ImmutableList.of(
            "//:app"));
    expectGenruleOutput(
        ":echo_with_kind_is_library",
        ImmutableList.of(
            "//:lib_a",
            "//:lib_b"));
  }

  @Test
  public void depsFromDepsQuery() throws Exception {
    expectGenruleOutput(
        ":echo_with_deps",
        ImmutableList.of(
            "//:app",
            "//:lib_a",
            "//:lib_b",
            "//annotations:proc"));
  }

  @Test
  public void depsFromDepsQueryWithDep1() throws Exception {
    expectOutputPathsGenruleOutput(
        ":echo_with_deps_1",
        ImmutableList.of(
            ":app",
            ":lib_a"));
  }

  @Test
  public void depsFromGenruleTransitive() throws Exception {
    expectGenruleOutput(
        ":echo_with_genrule_deps",
        ImmutableList.of(
            "//:app",
            "//:echo_with_annotation_processor_is_proc",
            "//:lib_a",
            "//:lib_b",
            "//annotations:proc"));
  }

  @Test
  public void filteredFromSet() throws Exception {
    expectOutputPathsGenruleOutput(
        ":echo_from_filtered_set",
        ImmutableList.of(
            "//annotations:proc",
            "//:app",
            "//:lib_a"));
  }

  @Test
  public void testQueryResultsAreInvalidatedWhenDirectDepChanges() throws Exception {
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");

    // Now, build again, assert we get the previous result
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();
    workspace.getBuildLog().assertNotTargetBuiltLocally("//:echo_deps_of_a");

    // Now, edit lib_a, which is the only explictly mentioned target and assert the query is run
    workspace.replaceFileContents("A.java", "// method", "public static void foo() {}");
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:lib_a");
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
    workspace.replaceFileContents("B.java", "// method", "public static void foo() {}");
    workspace.runBuckCommand("build", "//:echo_deps_of_a").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:lib_b");
    workspace.getBuildLog().assertTargetBuiltLocally("//:echo_deps_of_a");
  }

  @Test
  public void testQueryResultsReflectTransitiveChanges() throws Exception {
    Assume.assumeFalse(Platform.detect().equals(Platform.WINDOWS));
    // Assert the query result starts empty
    expectGenruleOutput(
        ":echo_with_has_debug_flag",
        ImmutableList.of());

    // Now edit the annotation processor target and add the flag
    workspace.replaceFileContents("annotations/BUCK", "#placeholder", "extra_arguments=['-g']");

    // Assert that the query output updates
    expectGenruleOutput(
        ":echo_with_has_debug_flag",
        ImmutableList.of(
            "//annotations:proc"));
  }

  private void expectOutputPathsGenruleOutput(
      String genrule,
      List<String> expectedOutputs)
      throws Exception {
    expectGenruleOutput(genrule, expectedOutputs.stream()
        .map(this::getOutputFile)
        .map(Path::toString)
        .collect(Collectors.toList()));
  }

  private void expectGenruleOutput(
      String genrule,
      List<String> expectedOutputs)
      throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", genrule);
    buildResult.assertSuccess();

    String outputFileContents = workspace.getFileContents(getOutputFile(genrule));
    List<String> actualOutput = Arrays.stream(outputFileContents.split("\\s"))
        .map(String::trim)
        .collect(Collectors.toList());
    assertEquals(expectedOutputs, actualOutput);
  }

  private Path getOutputFile(String targetName) {
    try {
      ProjectWorkspace.ProcessResult buildResult =
          workspace.runBuckCommand("targets", targetName, "--show-output", "--json");
      buildResult.assertSuccess();
      JsonNode jsonNode = objectMapper.reader().readTree(buildResult.getStdout()).get(0);
      assert jsonNode.has("buck.outputPath");
      return Paths.get(jsonNode.get("buck.outputPath").asText());
    } catch (Exception e) {
      fail(e.getMessage());
      return Paths.get("");
    }
  }
}
