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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
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
    assertEquals(workspace.getFileContents("stdout-config"), result.getStdout());
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
    assertEquals(workspace.getFileContents("stdout-config.json").trim(), result.getStdout());
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
    assertEquals(workspace.getFileContents("stdout-config.json").trim(), result.getStdout());
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
    assertEquals(workspace.getFileContents("stdout-buckconfig"), result.getStdout());
  }

  @Test
  public void testConfigBuckConfigWithCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_config", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("audit", "config", "secondary//second_section.some_property");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout-cell-buckconfig"), result.getStdout());
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
    assertThat(result.getStderr(), containsString("--json and --tab cannot both be specified"));
  }
}
