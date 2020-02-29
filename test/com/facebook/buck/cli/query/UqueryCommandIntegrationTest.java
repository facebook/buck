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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class UqueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "unconfigured_query", tmp);
    workspace.setUp();
  }

  @Test
  public void testWithoutSelectsSimple() {
    assertBehavesLikeConfiguredQuery("//:dummy_without_selects");
  }

  @Test
  public void testWithoutSelects() {
    ProcessResult resultUncofigured =
        workspace.runBuckCommand(
            "uquery-dont-use", "kind('.*', deps(%Ss))", "//:dummy_without_selects");
    resultUncofigured.assertSuccess();

    ProcessResult result =
        workspace.runBuckCommand("query", "kind('.*', deps(%Ss))", "//:dummy_without_selects");
    result.assertSuccess();

    assertEquals(resultUncofigured.getStdout(), result.getStdout());
  }

  @Test
  public void testWithoutSelectsJson() {
    ProcessResult resultJsonUncofigured =
        workspace.runBuckCommand(
            "uquery-dont-use", "//:dummy_without_selects",
            "--output-attributes", ".*");
    resultJsonUncofigured.assertSuccess();

    ProcessResult resultJson =
        workspace.runBuckCommand(
            "query", "//:dummy_without_selects",
            "--output-attributes", "(?!buck.target_configurations).*");
    resultJson.assertSuccess();

    assertEquals(resultJsonUncofigured.getStdout(), resultJson.getStdout());
  }

  @Test
  public void testOneSelectSimple() {
    assertBehavesLikeConfiguredQuery("//:dummy_with_one_select");
  }

  @Test
  public void testOneSelect() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "kind('.*', deps(%Ss))", "//:dummy_with_one_select");
    processResult.assertSuccess();

    sortOutputLinesAndCompare(
        processResult.getStdout(), workspace.getFileContents("one_select.txt"));
  }

  @Test
  public void testOneSelectJson() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "//:dummy_with_one_select",
            "--output-attributes", "buck.type|deps|name");
    processResult.assertSuccess();

    assertThat(
        parseJSON(workspace.getFileContents("one_select.json")),
        is(equalTo(parseJSON(processResult.getStdout()))));
  }

  @Test
  public void testListPlusSelectSimple() {
    assertBehavesLikeConfiguredQuery("//:dummy_list_plus_select");
  }

  @Test
  public void testListPlusSelect() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "kind('.*', deps(%Ss))", "//:dummy_list_plus_select");
    processResult.assertSuccess();

    sortOutputLinesAndCompare(
        processResult.getStdout(), workspace.getFileContents("list_plus_select.txt"));
  }

  @Test
  public void testListPlusSelectJson() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "//:dummy_list_plus_select",
            "--output-attributes", "buck.type|deps|name");

    assertThat(
        parseJSON(workspace.getFileContents("list_plus_select.json")),
        is(equalTo(parseJSON(processResult.getStdout()))));
  }

  @Test
  public void testMultipleConcatsSimple() {
    assertBehavesLikeConfiguredQuery("//:dummy_multiple_concats");
  }

  @Test
  public void testMultipleConcats() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "kind('.*', deps(%Ss))", "//:dummy_multiple_concats");
    processResult.assertSuccess();

    sortOutputLinesAndCompare(
        processResult.getStdout(), workspace.getFileContents("multiple_concats.txt"));
  }

  @Test
  public void testMultipleConcatsJson() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "//:dummy_multiple_concats",
            "--output-attributes", "buck.type|deps|name");

    assertThat(
        parseJSON(workspace.getFileContents("multiple_concats.json")),
        is(equalTo(parseJSON(processResult.getStdout()))));
  }

  @Test
  public void testDepsWithSelectsSimple() {
    assertBehavesLikeConfiguredQuery("//:dummy_deps_with_selects");
  }

  @Test
  public void testDepsWithSelects() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use", "kind('.*', deps(%Ss))", "//:dummy_deps_with_selects");
    processResult.assertSuccess();

    sortOutputLinesAndCompare(
        processResult.getStdout(), workspace.getFileContents("deps_with_selects.txt"));
  }

  @Test
  public void testDepsWithSelectsJson() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use",
            "kind('.*', deps(%Ss))",
            "//:dummy_deps_with_selects",
            "--output-attributes",
            "buck.type|deps|name");

    assertThat(
        parseJSON(workspace.getFileContents("deps_with_selects.json")),
        is(equalTo(parseJSON(processResult.getStdout()))));
  }

  @Test
  public void testTargetCompatibilityIgnored() throws IOException {
    // Explicitly test that unconfigured query does not filter targets
    ProcessResult processResult =
        workspace.runBuckCommand(
            "uquery-dont-use",
            "--target-platforms=//compatible/config:banana-platform",
            "//compatible:");
    processResult.assertSuccess();

    sortOutputLinesAndCompare(processResult.getStdout(), "//compatible:incompatible_with_config\n");
  }

  private void assertBehavesLikeConfiguredQuery(String target) {
    ProcessResult resultUncofigured = workspace.runBuckCommand("uquery-dont-use", target);
    resultUncofigured.assertSuccess();

    ProcessResult result = workspace.runBuckCommand("query", target);
    result.assertSuccess();

    assertEquals(resultUncofigured.getStdout(), result.getStdout());
  }

  private void sortOutputLinesAndCompare(String output, String expected) {
    String[] linesOutput = output.split("\n");
    Arrays.sort(linesOutput);

    String[] linesExpected = expected.split("\n");
    assertEquals(linesOutput.length, linesExpected.length);

    for (int i = 0; i < linesOutput.length; ++i) {
      assertEquals(linesExpected[i].trim(), linesOutput[i].trim());
    }
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

  @Test
  public void ownerShouldIncludeIncompatibleTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "query_command_with_incompatible_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery-dont-use", "owner('A.java')", "--target-platforms", "//:linux_platform");

    result.assertSuccess();
    assertEquals(
        Joiner.on(System.lineSeparator()).join(ImmutableList.of("//:lib_linux", "//:lib_osx")),
        result.getStdout().trim());
  }
}
