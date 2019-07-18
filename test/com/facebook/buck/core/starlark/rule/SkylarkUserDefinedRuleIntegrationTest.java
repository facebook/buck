/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class SkylarkUserDefinedRuleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void implementationFunctionIsCalledWithCtx() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_is_called", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:bar").assertSuccess();
    ProcessResult failureRes = workspace.runBuckBuild("//foo:baz").assertFailure();
    assertThat(
        failureRes.getStderr(), Matchers.containsString("Expected to be called with name 'bar'"));
  }

  @Test
  public void implementationFunctionHasAccessToAttrs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_has_correct_attrs_in_ctx", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:").assertSuccess();
  }

  @Test
  public void printsProperly() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "print_works_in_impl", tmp);

    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//foo:prints").assertSuccess();
    assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(
                ".*^DEBUG: \\S+defs.bzl:4:5: printing at debug level.*",
                Pattern.MULTILINE | Pattern.DOTALL)));
  }

  @Test
  public void implementationFunctionCanDeclareFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_declares_artifacts", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:valid_filename").assertSuccess();
  }

  @Test
  public void implementationDeclareFilesFailsOnInvalidFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_declares_artifacts", tmp);

    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//foo:not_a_path").assertFailure().getStderr(),
        Matchers.containsString("Invalid path"));
    assertThat(
        workspace.runBuckBuild("//foo:rejected_path").assertFailure().getStderr(),
        Matchers.containsString("attempted to traverse upwards"));
  }

  @Test
  public void implementationWritesFilesProperly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_writes_files", tmp);

    workspace.setUp();
    DefaultProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Path exePath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:exe"))
            .resolve("bar")
            .resolve("exe.sh");
    Path textPath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:text"))
            .resolve("bar")
            .resolve("text.txt");
    Path withSpacesPath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:with_spaces"))
            .resolve("bar")
            .resolve("with spaces.txt");

    assertFalse(filesystem.exists(exePath));
    assertFalse(filesystem.exists(textPath));

    workspace.runBuckBuild("//foo:exe", "//foo:text", "//foo:with_spaces").assertSuccess();

    assertEquals("exe content", filesystem.readFileIfItExists(filesystem.resolve(exePath)).get());
    assertEquals("text content", filesystem.readFileIfItExists(filesystem.resolve(textPath)).get());
    assertEquals(
        "with spaces content",
        filesystem.readFileIfItExists(filesystem.resolve(withSpacesPath)).get());
    // Executable works a bit differently on windows
    if (!Platform.isWindows()) {
      assertTrue(filesystem.isExecutable(exePath));
      assertFalse(filesystem.isExecutable(textPath));
      assertFalse(filesystem.isExecutable(withSpacesPath));
    }
  }

  @Test
  public void builtInProvidersAreAvailableAtAnalysisTime() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_return_values", tmp);

    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild("//foo:can_use_providers_in_impl").assertSuccess();
    assertThat(result.getStderr(), Matchers.containsString("in bzl: DefaultInfo("));
  }

  @Test
  public void returnsAnErrorWhenNonListIsReturned() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_return_values", tmp);

    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//foo:return_non_list").assertFailure().getStderr(),
        Matchers.containsString("is not of expected type"));
  }

  @Test
  public void returnsAnErrorWhenItemInListIsNotProviderInfo() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_return_values", tmp);

    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//foo:return_non_info_in_list").assertFailure().getStderr(),
        Matchers.containsString("expected type 'SkylarkProviderInfo'"));
  }

  @Test
  public void returnsAnErrorWhenDuplicateProvidersReturned() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_return_values", tmp);

    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//foo:return_duplicate_info_types").assertFailure().getStderr(),
        Matchers.containsString("returned two or more Info objects"));
  }

  @Test
  public void dependenciesAreAdded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_deps", tmp);

    workspace.setUp();

    ProcessResult depsQueryRes =
        workspace
            .runBuckCommand(
                "query",
                "--json",
                "deps(%s)",
                "//with_source_list:with_default_srcs",
                "//with_source_list:with_explicit_srcs")
            .assertSuccess();

    ProcessResult inputsQueryRes =
        workspace
            .runBuckCommand(
                "query",
                "--json",
                "inputs(%s)",
                "//with_source_list:with_default_srcs",
                "//with_source_list:with_explicit_srcs")
            .assertSuccess();

    BiFunction<JsonNode, String, ImmutableList<String>> toList =
        (JsonNode node, String field) ->
            Streams.stream(node.get(field).elements())
                .map(JsonNode::asText)
                .collect(ImmutableList.toImmutableList());

    JsonNode deps = ObjectMappers.READER.readTree(depsQueryRes.getStdout());
    JsonNode inputs = ObjectMappers.READER.readTree(inputsQueryRes.getStdout());

    assertThat(
        toList.apply(deps, "//with_source_list:with_default_srcs"),
        Matchers.containsInAnyOrder(
            "//with_source_list:default", "//with_source_list:with_default_srcs"));

    assertThat(
        toList.apply(deps, "//with_source_list:with_explicit_srcs"),
        Matchers.containsInAnyOrder(
            "//with_source_list:other", "//with_source_list:with_explicit_srcs"));

    assertThat(
        toList.apply(inputs, "//with_source_list:with_default_srcs"),
        Matchers.containsInAnyOrder(Paths.get("with_source_list", "default_src.txt").toString()));

    assertThat(
        toList.apply(inputs, "//with_source_list:with_explicit_srcs"),
        Matchers.containsInAnyOrder(Paths.get("with_source_list", "some_src.txt").toString()));
  }

  @Test
  public void implementationGetsSourceFromSourceList() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_gets_artifacts_from_source_list", tmp);
    DefaultProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    workspace.setUp();

    workspace.runBuckBuild("//:with_sources").assertSuccess();

    assertEquals(
        "contents2",
        workspace.getFileContents(
            BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//:with_sources"))
                .resolve("out2.txt")));
  }

  @Test
  public void failsWhenInvalidArgTypesGiven() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "args", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//:add").assertSuccess();
    workspace.runBuckBuild("//:add_all").assertSuccess();
    assertThat(
        workspace.runBuckBuild("//:add_failure").assertFailure().getStderr(),
        Matchers.containsString("expected value of type"));
    assertThat(
        workspace.runBuckBuild("//:add_all_failure").assertFailure().getStderr(),
        Matchers.containsString("Invalid command line argument type"));
  }

  private static ImmutableList<String> splitStderr(ProcessResult result) {
    return Splitter.on(System.lineSeparator()).splitToList(result.getStderr().trim()).stream()
        .map(String::trim)
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void implementationCanRunCommands() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_runs_actions", tmp);

    workspace.setUp();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    ProcessResult zeroResult = workspace.runBuckBuild("//foo:returning_zero").assertSuccess();
    ProcessResult oneResult = workspace.runBuckBuild("//foo:returning_one").assertFailure();
    ProcessResult zeroWithEnvResult =
        workspace.runBuckBuild("//foo:returning_zero_with_env").assertSuccess();
    ProcessResult oneWithEnvResult =
        workspace.runBuckBuild("//foo:returning_one_with_env").assertFailure();

    String[] expectedOneResult =
        new String[] {
          "Message on stderr",
          "arg[--out]",
          String.format(
              "arg[%s]",
              filesystem.resolve(
                  BuildPaths.getGenDir(
                          filesystem, BuildTargetFactory.newInstance("//foo:returning_one"))
                      .resolve("out.txt"))),
          "arg[--bar]",
          "arg[some]",
          "arg[arg]",
          "arg[here]",
          String.format("PWD: %s", filesystem.getRootPath().toString())
        };

    String[] expectedOneWithEnvResult =
        new String[] {
          "Message on stderr",
          "arg[--out]",
          String.format(
              "arg[%s]",
              filesystem.resolve(
                  BuildPaths.getGenDir(
                          filesystem,
                          BuildTargetFactory.newInstance("//foo:returning_one_with_env"))
                      .resolve("out.txt"))),
          "arg[--bar]",
          "arg[some]",
          "arg[arg]",
          "arg[here]",
          String.format("PWD: %s", filesystem.getRootPath().toString()),
          "CUSTOM_ENV: CUSTOM"
        };

    assertThat(splitStderr(oneResult), Matchers.containsInRelativeOrder(expectedOneResult));
    assertThat(
        splitStderr(oneWithEnvResult), Matchers.containsInRelativeOrder(expectedOneWithEnvResult));
    // Make sure we're not spuriously printing output from the program on success
    assertFalse(zeroResult.getStderr().contains("CUSTOM_ENV"));
    assertFalse(zeroWithEnvResult.getStderr().contains("CUSTOM_ENV"));
  }
}
