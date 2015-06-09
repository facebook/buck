/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class MainIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testBuckNoArgs() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "empty_project", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand();

    result.assertFailure();
    assertEquals(
        "When the user does not specify any arguments, the usage information should be displayed",
        getUsageString(),
        result.getStderr());
  }

  @Test
  public void testBuckHelp() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "empty_project", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand();

    result.assertFailure();
    assertEquals("Users instinctively try running `buck --help`, so it should print usage info.",
        getUsageString(),
        result.getStderr());
  }

  @Test
  public void testIncludesOverride() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "includes_override", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--buildfile:includes //includes.py");

    result.assertSuccess();
  }

  private String getUsageString() {
    return Joiner.on('\n').join(
        "buck build tool",
        "usage:",
        "  buck [options]",
        "  buck command --help",
        "  buck command [command-options]",
        "available commands:",
        "  audit       lists the inputs for the specified target",
        "  build       builds the specified target",
        "  cache       makes calls to the artifact cache",
        "  clean       deletes any generated files",
        "  fetch       downloads remote resources to your local machine",
        "  install     builds and installs an application",
        "  project     generates project configuration files for an IDE",
        "  quickstart  generates a default project directory",
        "  run         runs a target as a command",
        "  server      query and control the http server",
        "  targets     prints the list of buildable targets",
        "  test        builds and runs the tests for the specified target",
        "  uninstall   uninstalls an APK",
        "options:",
        " --help         : Shows this screen and exits.",
        " --version (-V) : Show version number.",
        "");
  }
}
