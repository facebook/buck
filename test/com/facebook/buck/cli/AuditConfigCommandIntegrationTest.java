/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.StringReader;
import org.ini4j.Ini;
import org.junit.Rule;
import org.junit.Test;

public class AuditConfigCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testTabbedUI() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "config",
            "--tab",
            "missing_section.badvalue",
            "ignored_section.dotted.value",
            "ignored_section.short_value",
            "ignored_section.long_value");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-config"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
    assertThat(result.getStderr(), containsString("missing_section is not a valid section string"));
  }

  @Test
  public void testConfigJsonUI() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "config",
            "--json",
            "missing_section.badvalue",
            "ignored_section.dotted.value",
            "ignored_section.short_value",
            "ignored_section.long_value");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-config.json").trim(),
        equalToIgnoringPlatformNewlines(result.getStdout()));
    assertThat(result.getStderr(), containsString("missing_section is not a valid section string"));
  }

  @Test
  public void testConfigJsonUIWithWholeSection() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "audit", "config", "--json", "missing_section.badvalue", "ignored_section");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-config.json").trim(),
        equalToIgnoringPlatformNewlines(result.getStdout()));
    assertThat(result.getStderr(), containsString("missing_section is not a valid section string"));
  }

  @Test
  public void testConfigBuckConfigUI() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "config",
            "missing_section.badvalue",
            "ignored_section",
            "ignored_section.dotted.value",
            "second_section.some_property",
            "ignored_section.short_value",
            "ignored_section.long_value");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-buckconfig"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
    assertThat(result.getStderr(), containsString("missing_section is not a valid section string"));
  }

  @Test
  public void testConfigBuckConfigWithCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "config", "secondary//second_section.some_property");
    result.assertSuccess();
    assertThat(
        workspace.getFileContents("stdout-cell-buckconfig"),
        equalToIgnoringPlatformNewlines(result.getStdout()));
  }

  @Test
  public void testIncludesWithCell() throws IOException {
    // This test also verifies that the include paths are constructed relative
    // to the buck config file that contains the include.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "config", "secondary//included_section");
    result.assertSuccess();
    assertThat(result.getStdout(), containsString("included_section"));
  }

  @Test
  public void testIncludesWithKeyValueOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "config", "included_inline_section.included_key");
    result.assertSuccess();
    assertThat(result.getStdout(), containsString("real value"));
  }

  @Test
  public void testErrorOnBothTabAndJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "config",
            "--tab",
            "--json",
            "missing_section.badvalue",
            "ignored_section",
            "ignored_section.dotted.value",
            "second_section.some_property",
            "ignored_section.short_value",
            "ignored_section.long_value");
    result.assertExitCode("--json and --tab are incompatible", ExitCode.COMMANDLINE_ERROR);
    assertThat(result.getStderr(), containsString("cannot be used"));
  }

  @Test
  public void testConfigAuditEntireConfig() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("audit", "config");

    // Use low level ini parser to build config.
    Ini audit_ini = new Ini(new StringReader(result.getStdout()));
    // Ini object doesn't really provide a good way to compare 2 ini files.
    // Convert that into immutable map so that we can compare sorted maps instead.
    ImmutableMap.Builder<String, ImmutableMap<String, String>> audit_config =
        ImmutableMap.builder();
    audit_ini.forEach(
        (section_name, section) -> {
          ImmutableMap.Builder<String, String> section_builder = ImmutableMap.builder();
          section.forEach((k, v) -> section_builder.put(k, v));
          audit_config.put(section_name, section_builder.build());
        });

    assertEquals(workspace.getConfig().getSectionToEntries(), audit_config.build());
  }

  @Test
  public void testAuditEntireConfigInJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("audit", "config", "--json");
    result.assertSuccess();
    JsonNode jsonNode = ObjectMappers.READER.readTree(result.getStdout());
    // Our integration tests add a few values (one of which changes every time) so we can't do a
    // direct comparison.
    // Instead, just verify a few known values
    assertTrue(jsonNode.has("ignored_section.dotted.value"));
    assertTrue(jsonNode.has("ignored_section.short_value"));
    assertTrue(jsonNode.has("second_section.some_property"));
    // Make sure that we can handle two sections with identical keys
    assertEquals(jsonNode.get("python#py2.interpreter").asText(), "python2");
    assertEquals(jsonNode.get("python#py3.interpreter").asText(), "python3");
  }
}
