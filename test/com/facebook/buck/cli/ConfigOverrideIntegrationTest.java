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

import static com.facebook.buck.util.string.MoreStrings.withoutSuffix;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConfigOverrideIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testConfigOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand("targets", "--config", "buildfile.includes=//includes.py", "//...")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "--config", "//buildfile.includes=//includes.py", "//...")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "--config", "repo//buildfile.includes=//includes.py", "//...")
        .assertSuccess();
  }

  @Test
  public void testNoRepositoriesConfigOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckCommand("targets", "--config", "repositories.secondary=../secondary");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "Overriding repository locations from the command line "
                + "is not supported. Please place a .buckconfig.local in the appropriate location and "
                + "use that instead."));
  }

  @Test
  public void testConfigRemoval() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_removals", tmp);
    workspace.setUp();

    // BUCK file defines `ide` as idea, now lets switch to undefined one!
    // It should produce exception as we want explicit ide setting.
    ProcessResult result = workspace.runBuckCommand("project", "--config", "project.ide=");
    result.assertExitCode("project IDE is not specified", ExitCode.COMMANDLINE_ERROR);
  }

  @Test
  public void testConfigFileOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    workspace.setUp();

    Files.write(
        tmp.newFile("buckconfig"), ImmutableList.of("[buildfile]", "  includes = //includes.py"));

    workspace.runBuckCommand("targets", "--config-file", "buckconfig", "//...").assertSuccess();
    workspace.runBuckCommand("targets", "--config-file", "//buckconfig", "//...").assertSuccess();
    workspace
        .runBuckCommand("targets", "--config-file", "repo//buckconfig", "//...")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "--config-file", "*=repo//buckconfig", "//...")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "--config-file", "repo=repo//buckconfig", "//...")
        .assertSuccess();
    // Apply config to current cell.
    workspace
        .runBuckCommand("targets", "--config-file", "//=repo//buckconfig", "//...")
        .assertSuccess();
  }

  @Test
  public void testConfigFileOverrideInvalidCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    Files.write(
        tmp.newFile("buckconfig"), ImmutableList.of("[buildfile]", "  includes = //includes.py"));
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckCommand(
            "targets", "--config-file", "no_such_repo=repo//buckconfig", "//...");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("Unknown cell"));
  }

  @Test
  public void testConfigFileOverrideWithMultipleFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "alias", tmp);
    workspace.setUp();
    String myServer = "//:my_server";
    String myClient = "//:my_client";

    Files.write(tmp.newFile("buckconfig1"), ImmutableList.of("[alias]", "  server = " + myServer));
    Files.write(tmp.newFile("buckconfig2"), ImmutableList.of("[alias]", "  client = " + myClient));

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "alias",
            "--list-map",
            "--config-file",
            "buckconfig1",
            "--config-file",
            "buckconfig2");
    result.assertSuccess();

    // Remove trailing newline from stdout before passing to Splitter.
    String stdout = result.getStdout();
    stdout = withoutSuffix(stdout, System.lineSeparator());

    List<String> aliases = Splitter.on(System.lineSeparator()).splitToList(stdout);
    assertEquals(
        ImmutableSet.of(
            "foo = //:foo_example",
            "bar = //:bar_example",
            "server = " + myServer,
            "client = " + myClient),
        ImmutableSet.copyOf(aliases));
  }

  @Test
  public void testConfigOverridesOrderShouldMatter() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    workspace.setUp();

    Files.write(
        tmp.newFile("buckconfig1"),
        ImmutableList.of("[buildfile]", "  includes = //invalid_includes.py"));
    Files.write(
        tmp.newFile("buckconfig2"), ImmutableList.of("[buildfile]", "  includes = //includes.py"));

    workspace
        .runBuckCommand(
            "targets",
            "--config",
            "buildfile.includes=//invalid_includes.py",
            "--config-file",
            "buckconfig1",
            "--config",
            "buildfile.includes=//invalid_includes.py",
            "--config-file",
            "buckconfig2",
            "//...")
        .assertSuccess();
  }
}
