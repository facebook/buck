/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
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
                .build(),
            ImmutableMap.builder()
                .put("name", "//:other")
                .put("type", "genrule")
                .put("buildDeps", ImmutableList.of())
                .build()));
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
  @SuppressWarnings("unchecked")
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
}
