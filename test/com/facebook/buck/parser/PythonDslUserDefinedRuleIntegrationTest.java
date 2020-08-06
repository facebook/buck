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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PythonDslUserDefinedRuleIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  ProjectWorkspace workspace;

  @Before
  public void setUp() {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "python_user_defined_rules", tmp);
  }

  @Test
  public void ruleFunctionFailsIfNotEnabled() throws IOException {
    // All the configuration permutations are tested more thoroughly in ParserConfigTest
    workspace.addBuckConfigLocalOption("parser", "default_build_file_syntax", "PYTHON_DSL");
    workspace.addBuckConfigLocalOption("parser", "polyglot_parsing_enabled", "true");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");
    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "DISABLED");
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//simple:rule1")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString("name 'rule' is not defined"));
  }

  @Test
  public void ruleFunctionFailsInBuildFile() throws IOException {
    workspace.setUp();
    assertThat(
        workspace
            .runBuckCommand("build", "//errors/rule_in_build/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString("rule()` is only allowed in extension files"));
  }

  @Test
  public void udrCallableFailsInExtensionFile() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/udr_call_in_bzl/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString(
            "example_rule may not be called from the top level of extension files"));
  }

  @Test
  public void defaultValuesAreUsedFromAttrsIfNotProvided() throws IOException {
    workspace.setUp();

    ImmutableMap<String, ImmutableMap<String, Object>> expected =
        ImmutableMap.of(
            "//attrs:system_defaults",
            ImmutableMap.of(
                "param_int",
                0,
                "param_int_list",
                ImmutableList.of(),
                "param_string",
                "",
                "param_string_list",
                ImmutableList.of(),
                "param_bool",
                false),
            "//attrs:system_defaults_overridden",
            ImmutableMap.of(
                "param_int",
                2,
                "param_int_list",
                ImmutableList.of(2, 3),
                "param_string",
                "string2",
                "param_string_list",
                ImmutableList.of("string2", "string3"),
                "param_bool",
                true),
            "//attrs:defaults",
            ImmutableMap.<String, Object>builder()
                .put("param_int", 1)
                .put("param_int_list", ImmutableList.of(1))
                .put("param_string", "string1")
                .put("param_string_list", ImmutableList.of("string1"))
                .put("param_bool", true)
                .put("param_source_list", ImmutableList.of("src1.txt"))
                .put("param_source", "src1.txt")
                .put("param_dep", ":src1")
                .put("param_dep_list", ImmutableList.of(":src1"))
                .put("param_output", "out1.txt")
                .put("param_output_list", ImmutableList.of("out1.txt"))
                .build(),
            "//attrs:defaults_overridden",
            ImmutableMap.<String, Object>builder()
                .put("param_int", 2)
                .put("param_int_list", ImmutableList.of(2))
                .put("param_string", "string2")
                .put("param_string_list", ImmutableList.of("string2", "string3"))
                .put("param_bool", true)
                .put("param_source_list", ImmutableList.of("src2.txt", "src3.txt"))
                .put("param_source", "src2.txt")
                .put("param_dep", ":src2")
                .put("param_dep_list", ImmutableList.of(":src2", ":src3"))
                .put("param_output", "out2.txt")
                .put("param_output_list", ImmutableList.of("out2.txt", "out3.txt"))
                .build());

    String rawOut =
        workspace
            .runBuckCommand("query", "//attrs/...", "--json", "--output-attribute=param_.*")
            .assertSuccess()
            .getStdout();
    JsonNode out = ObjectMappers.READER.readTree(rawOut);
    JsonNode expected2 =
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(expected));

    assertEquals(expected2, out);
  }

  @Test
  public void acceptsCommonBuildArgs() throws IOException {
    workspace.setUp();

    String out =
        workspace
            .runBuckCommand(
                "query",
                "--json",
                "--output-attribute=name|default_target_platform|labels|contacts|compatible_with",
                "%s",
                "//simple:with_implicits",
                "//simple:with_test_implicits")
            .assertSuccess()
            .getStdout();

    ImmutableMap<String, Object> expectedNonTest =
        ImmutableMap.of(
            "name",
            "with_implicits",
            "default_target_platform",
            ":x86_64-platform",
            "labels",
            ImmutableList.of("foo"),
            "compatible_with",
            ImmutableList.of());
    ImmutableMap<String, Object> expectedTest =
        ImmutableMap.of(
            "name",
            "with_test_implicits",
            "default_target_platform",
            ":x86_64-platform",
            "labels",
            ImmutableList.of("foo"),
            "compatible_with",
            ImmutableList.of(),
            "contacts",
            ImmutableList.of("oncall@example.com"));

    ImmutableMap<String, ImmutableMap<String, Object>> expected =
        ImmutableMap.of(
            "//simple:with_implicits",
            expectedNonTest,
            "//simple:with_test_implicits",
            expectedTest);

    assertEquals(
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(expected)),
        ObjectMappers.READER.readTree(out));
  }

  @Test
  public void failsIfRequiredParamsNotProvided() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/missing_mandatory_params/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString(
            "Mandatory parameter 'mandatory' for //simple:simple_rule.bzl:simple_rule was missing"));
  }

  @Test
  public void errorsOnExtraParams() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/extra_params/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString(
            "Unexpected extra parameter(s) 'extra_parameter' provided for //simple:simple_rule.bzl:simple_rule"));
  }

  @Test
  public void printsOptionalCommonParamsIfProvided() throws IOException {
    workspace.setUp();

    String output =
        workspace
            .runBuckCommand("query", "--json", "--output-attribute=.*", "//simple:with_label")
            .assertSuccess()
            .getStdout();

    assertEquals(
        ImmutableList.of("foo"),
        Streams.stream(
                ObjectMappers.READER
                    .readTree(output)
                    .get("//simple:with_label")
                    .get("labels")
                    .elements())
            .map(JsonNode::asText)
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void doesNotPrintOptionalCommonParamsIfNotProvided() throws IOException {
    workspace.setUp();

    String output =
        workspace
            .runBuckCommand("query", "--json", "--output-attribute=.*", "//simple:without_label")
            .assertSuccess()
            .getStdout();

    assertNull(ObjectMappers.READER.readTree(output).get("//simple:without_label").get("labels"));
  }

  @Test
  public void rejectsInvalidParameterNames() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/invalid_attr_name/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString("1234isntvalid is not a valid python identifier"));
  }

  @Test
  public void hasCorrectBuckTypeAtAllIncludeLevels() throws IOException {
    workspace.setUp();

    JsonNode js =
        ObjectMappers.READER.readTree(
            workspace
                .runBuckCommand("query", "--output-attribute=buck.type", "--json", "//complex/...")
                .assertSuccess()
                .getStdout());

    assertEquals(
        "//complex:bar.bzl:some_rule",
        js.get("//complex:complex_rule_wrapper").get("buck.type").asText());
    assertEquals(
        "//complex:bar.bzl:some_rule", js.get("//complex:complex_rule").get("buck.type").asText());
    assertEquals(
        "//complex:bar.bzl:some_rule",
        js.get("//complex:some_rule_wrapper").get("buck.type").asText());
    assertEquals(
        "//complex:bar.bzl:some_rule", js.get("//complex:some_rule").get("buck.type").asText());
  }

  @Test
  public void printsErrorFromSkylarkIfInvalid() throws IOException {
    workspace.setUp();

    // TODO(pjameson): We do not currently pass the location from the parser -> UDR implementation
    //                 This makes the error a little less useful right now. Once that attribute is
    //                 added, we can make skylark error message more helpful.
    String stderr =
        workspace.runBuckBuild("//errors/skylark_errors/...").assertFailure().getStderr();
    assertThat(stderr, Matchers.containsString("zomg broken"));
    assertThat(stderr, Matchers.not(Matchers.containsString(".py")));
  }

  @Test
  public void skylarkValidatesDeclarations() throws IOException {
    workspace.setUp();

    String stderr =
        workspace
            .runBuckBuild("//errors/invalid_attr_default/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr();
    // Skylark error, not a python one
    assertThat(stderr, Matchers.containsString("invalid_attr.bzl:6:23"));
    assertThat(
        stderr, Matchers.containsString("expected value of type 'int' for parameter 'default"));
  }

  @Test
  public void implementationFunctionsAreRun() throws IOException {
    workspace.setUp();
    Path out = workspace.buildAndReturnOutput("//simple:rule1");
    assertEquals("rule1 string\nhidden_var", workspace.getFileContents(out));
  }

  @Test
  public void failsIfUnderscoreParamsPassed() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/hidden_params/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString(
            "Unexpected extra parameter(s) '_hidden' provided for //simple:simple_rule.bzl:simple_rule"));
  }

  @Test
  public void failsIfShadowingBuiltInAttributes() throws IOException {
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("build", "//errors/shadowing_name/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString(
            "name shadows a builtin attribute of the same name. Please remove it"));
    assertThat(
        workspace
            .runBuckCommand("build", "//errors/shadowing_buck_type/...")
            .assertExitCode(ExitCode.PARSE_ERROR)
            .getStderr(),
        Matchers.containsString("buck.type is not a valid python identifier"));
  }

  @Test
  public void udrRulesUseCellsProperly() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "python_user_defined_rules", tmp);
    workspace.setUp();

    JsonNode outRootCell =
        ObjectMappers.READER.readTree(
            workspace
                .runBuckCommand(
                    "query",
                    "--json",
                    "--output-attribute=buck.type",
                    "%s",
                    "cell//:in_cell",
                    "//with_cell:root_cell")
                .assertSuccess()
                .getStdout());

    JsonNode outCell =
        ObjectMappers.READER.readTree(
            workspace
                .runBuckCommand(
                    workspace.getDestPath().resolve("cell"),
                    "query",
                    "--json",
                    "--output-attribute=buck.type",
                    "%s",
                    "//:in_cell")
                .assertSuccess()
                .getStdout());

    // Cell name is pulled from the project root in the python dsl, and cell names are enforced
    // to be identical in all subcells
    assertEquals(
        "@cell//:simple_rule.bzl:simple_rule",
        outRootCell.get("//with_cell:root_cell").get("buck.type").asText());
    assertEquals(
        "@cell//:simple_rule.bzl:simple_rule",
        outRootCell.get("cell//:in_cell").get("buck.type").asText());

    // However when run from within a cell, we'll have a new root
    assertEquals(
        "//:simple_rule.bzl:simple_rule", outCell.get("//:in_cell").get("buck.type").asText());
  }
}
