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

package com.facebook.buck.cli;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AuditActionGraphCommandIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  @SuppressWarnings("unchecked")
  public void dumpsNodeAndDependencyInformationInJsonFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "actiongraph", "//:bin").assertSuccess();

    String json = result.getStdout();
    List<Map<String, Object>> root =
        (List<Map<String, Object>>) ObjectMappers.readValue(json, List.class);
    Assert.assertThat(
        root,
        Matchers.containsInAnyOrder(
            ImmutableMap.builder()
                .put("name", "//:bin")
                .put("type", "genrule")
                .put("buildDeps", ImmutableList.of("//:other"))
                .put(
                    "outputPath",
                    getLegacyGenPathForTarget("//:bin", workspace, "").resolve("bin").toString())
                .put(
                    "outputPaths",
                    ImmutableMap.of(
                        OutputLabel.defaultLabel().toString(),
                        asList(
                            getLegacyGenPathForTarget("//:bin", workspace, "")
                                .resolve("bin")
                                .toString())))
                .build(),
            ImmutableMap.builder()
                .put("name", "//:other")
                .put("type", "genrule")
                .put("buildDeps", ImmutableList.of())
                .put(
                    "outputPath",
                    getLegacyGenPathForTarget("//:other", workspace, "")
                        .resolve("other")
                        .toString())
                .put(
                    "outputPaths",
                    ImmutableMap.of(
                        OutputLabel.defaultLabel().toString(),
                        asList(
                            getLegacyGenPathForTarget("//:other", workspace, "")
                                .resolve("other")
                                .toString())))
                .build()));
  }

  @Test
  public void dumpsNamedOutputsInformationInJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();
    String targetName = "//:named_outputs";

    ProcessResult result =
        workspace
            .runBuckCommand("audit", "actiongraph", "--node-view", "extended", targetName)
            .assertSuccess();

    String json = result.getStdout();
    Map<String, Object> fields =
        (Map<String, Object>) ObjectMappers.readValue(json, List.class).get(0);

    Map<String, String> outputPaths = (Map<String, String>) fields.get("outputPaths");
    Assert.assertThat(outputPaths.entrySet(), Matchers.hasSize(2));

    Assert.assertThat(outputPaths.get(OutputLabel.defaultLabel().toString()), Matchers.nullValue());
    Assert.assertThat(
        outputPaths.get(OutputLabel.of("output1").toString()),
        Matchers.equalTo(
            asList(
                getLegacyGenPathForTarget(targetName, workspace, "").resolve("out1").toString())));
    Assert.assertThat(
        outputPaths.get(OutputLabel.of("output2").toString()),
        Matchers.equalTo(
            asList(
                getLegacyGenPathForTarget(targetName, workspace, "").resolve("out2").toString())));

    Assert.assertThat(fields.get("buck_output"), Matchers.equalTo(""));
    Assert.assertThat(
        fields.get("buck_outputs"),
        Matchers.equalTo(
            getExtendedNodeViewStringForNamedOutputs(
                ImmutableSortedMap.of(
                    OutputLabel.defaultLabel(),
                    "[]",
                    OutputLabel.of("output1"),
                    "out1",
                    OutputLabel.of("output2"),
                    "out2"),
                workspace.getProjectFileSystem(),
                targetName)));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void dumpsNodeAndDependencyInformationWithRuntimeDepsInJsonFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace
            .runBuckCommand("audit", "actiongraph", "--include-runtime-deps", "//:pybin")
            .assertSuccess();

    String json = result.getStdout();
    List<Map<String, Object>> root =
        (List<Map<String, Object>>) ObjectMappers.readValue(json, List.class);
    Assert.assertThat(
        root,
        Matchers.containsInAnyOrder(
            ImmutableMap.builder()
                .put("name", "//:pybin")
                .put("type", "python_packaged_binary")
                .put("buildDeps", ImmutableList.of())
                .put("runtimeDeps", ImmutableList.of("//:pylib"))
                .put(
                    "outputPath",
                    getLegacyGenPathForTarget("//:pybin", workspace, ".pex").toString())
                .build(),
            ImmutableMap.builder()
                .put("name", "//:pylib")
                .put("type", "python_library")
                .put("buildDeps", ImmutableList.of())
                .put("runtimeDeps", ImmutableList.of())
                .build()));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void dumpsNodeAndDependencyInformationWithOutputPathInJsonFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "actiongraph", "//:pybin").assertSuccess();

    String json = result.getStdout();
    List<Map<String, Object>> root =
        (List<Map<String, Object>>) ObjectMappers.readValue(json, List.class);
    Assert.assertThat(
        root,
        Matchers.containsInAnyOrder(
            ImmutableMap.builder()
                .put("name", "//:pybin")
                .put("type", "python_packaged_binary")
                .put("buildDeps", ImmutableList.of())
                .put(
                    "outputPath",
                    getLegacyGenPathForTarget("//:pybin", workspace, ".pex").toString())
                .build(),
            ImmutableMap.builder()
                .put("name", "//:pylib")
                .put("type", "python_library")
                .put("buildDeps", ImmutableList.of())
                .build()));
  }

  @Test
  public void dumpsNodeAndDependencyInformationInDotFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "actiongraph", "--dot", "//:bin").assertSuccess();

    String json = result.getStdout();
    Assert.assertThat(json, Matchers.startsWith("digraph "));
    Assert.assertThat(json, Matchers.containsString("\"//:bin\" -> \"//:other\""));
    Assert.assertThat(json, Matchers.endsWith("}" + System.lineSeparator()));
  }

  @Test
  public void dumpsNodeAndDependencyInformationWithRuntimeDepsInDotFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_action_graph", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace
            .runBuckCommand("audit", "actiongraph", "--dot", "--include-runtime-deps", "//:pybin")
            .assertSuccess();

    String json = result.getStdout();
    Assert.assertThat(json, Matchers.startsWith("digraph "));
    Assert.assertThat(json, Matchers.containsString("\"//:pybin\" -> \"//:pylib\""));
    Assert.assertThat(json, Matchers.endsWith("}" + System.lineSeparator()));
  }

  private AbsPath getLegacyGenPathForTarget(
      String buildTarget, ProjectWorkspace workspace, String suffix) throws IOException {
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    RelPath genDir =
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(), BuildTargetFactory.newInstance(buildTarget), "%s" + suffix);
    return tmp.getRoot().resolve(genDir);
  }

  /**
   * Returns a String in the following format:
   *
   * <ul>
   *   <li>Pair(target_name, output_path_relative_to_buck-out)
   * </ul>
   *
   * <p>Example:
   *
   * <ul>
   *   <li>Pair(//:named_outputs, buck-out/gen/ce9b6f2e/named_outputs/out1)
   * </ul>
   */
  private String getExtendedNodeViewStringForOutput(
      ProjectFilesystem filesystem, String targetName, String relPathToOutputDir) {
    Path output =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), BuildTargetFactory.newInstance(targetName), "%s")
            .resolve(relPathToOutputDir);
    return new StringBuilder("Pair(")
        .append(targetName)
        .append(", ")
        .append(output)
        .append(")")
        .toString();
  }

  /**
   * Returns a String in the following format:
   *
   * <ul>
   *   <li>{output_label=[Pair(target_name, output_path_relative_to_buck-out)]}
   * </ul>
   *
   * <p>Example:
   *
   * <ul>
   *   <li>{DEFAULT=[], output1=[Pair(//:named_outputs, buck-out/gen/ce9b6f2e/named_outputs/out1)]}
   * </ul>
   */
  private String getExtendedNodeViewStringForNamedOutputs(
      ImmutableSortedMap<OutputLabel, String> outputLabelsToPaths,
      ProjectFilesystem filesystem,
      String targetName) {
    StringBuilder sb = new StringBuilder("{");
    for (Map.Entry<OutputLabel, String> entry : outputLabelsToPaths.entrySet()) {
      sb.append(entry.getKey())
          .append("=")
          .append(
              entry.getValue().equals("[]")
                  ? entry.getValue()
                  : asList(
                      getExtendedNodeViewStringForOutput(filesystem, targetName, entry.getValue())))
          .append(", ");
    }
    if (!outputLabelsToPaths.isEmpty()) {
      sb.delete(sb.length() - 2, sb.length());
    }
    sb.append("}");
    return sb.toString();
  }

  private static String asList(String stringToWrap) {
    return new StringBuilder("[").append(stringToWrap).append("]").toString();
  }
}
