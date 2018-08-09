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
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
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
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class MainIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testBuckNoArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_project", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand();

    result.assertExitCode("nothing specified", ExitCode.COMMANDLINE_ERROR);
    assertThat(
        "When the user does not specify any arguments, the usage information should be displayed",
        result.getStderr(),
        containsString(getUsageString()));
  }

  @Test
  public void testBuckHelp() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_project", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand();

    result.assertExitCode("nothing specified", ExitCode.COMMANDLINE_ERROR);
    assertThat(
        "Users instinctively try running `buck --help`, so it should print usage info.",
        result.getStderr(),
        containsString(getUsageString()));
  }

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
    try {
      workspace.runBuckCommand("targets", "--config", "repositories.secondary=../secondary");
      fail("Did not expect to allow repositories override");
    } catch (HumanReadableException expected) {
      assertEquals(
          "Overriding repository locations from the command line "
              + "is not supported. Please place a .buckconfig.local in the appropriate location and "
              + "use that instead.",
          expected.getMessage());
    }
  }

  @Test
  public void testConfigRemoval() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_removals", tmp);
    workspace.setUp();
    // BUCK file defines `ide` as idea, now lets switch to undefined one!
    // It should produce exception as we want explicit ide setting.
    try {
      workspace.runBuckCommand("project", "--config", "project.ide=");
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.stringContainsInOrder(
              "Please specify ide using --ide option " + "or set ide in .buckconfig"));
    } catch (Exception e) {
      // other exceptions are not expected
      throw e;
    }
  }

  @Test
  public void testConfigFileOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "includes_override", tmp);
    workspace.setUp();

    Path arg = tmp.newFile("buckconfig");
    Files.write(arg, ImmutableList.of("[buildfile]", "  includes = //includes.py"));

    workspace.runBuckCommand("targets", "--config-file", arg.toString(), "//...").assertSuccess();

    workspace.runBuckCommand("targets", "--config-file", "//=" + arg, "//...").assertSuccess();

    workspace.runBuckCommand("targets", "--config-file", "repo//=" + arg, "//...").assertSuccess();
  }

  @Test
  public void testConfigFileOverrideWithMultipleFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "alias", tmp);
    workspace.setUp();
    String myServer = "//:my_server";
    String myClient = "//:my_client";

    Path arg1 = tmp.newFile("buckconfig1");
    Files.write(arg1, ImmutableList.of("[alias]", "  server = " + myServer));
    Path arg2 = tmp.newFile("buckconfig2");
    Files.write(arg2, ImmutableList.of("[alias]", "  client = " + myClient));

    ProcessResult result =
        workspace.runBuckCommand(
            "audit",
            "alias",
            "--list-map",
            "--config-file",
            arg1.toString(),
            "--config-file",
            arg2.toString());
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

    Path arg1 = tmp.newFile("buckconfig1");
    Files.write(arg1, ImmutableList.of("[buildfile]", "  includes = //invalid_includes.py"));
    Path arg2 = tmp.newFile("buckconfig2");
    Files.write(arg2, ImmutableList.of("[buildfile]", "  includes = //includes.py"));

    workspace
        .runBuckCommand(
            "targets",
            "--config",
            "buildfile.includes=//invalid_includes.py",
            "--config-file",
            arg1.toString(),
            "--config",
            "buildfile.includes=//invalid_includes.py",
            "--config-file",
            arg2.toString(),
            "//...")
        .assertSuccess();
  }

  private String getUsageString() {
    return String.join(
        System.lineSeparator(),
        "Description: ",
        "  Buck build tool",
        "",
        "Usage:",
        "  buck [<options>]",
        "  buck <command> --help",
        "  buck <command> [<command-options>]",
        "",
        "Available commands:",
        "  audit          lists the inputs for the specified target",
        "  build          builds the specified target",
        "  cache          makes calls to the artifact cache",
        "  cachedelete    Delete artifacts from the local and remote cache",
        "  clean          deletes any generated files and caches",
        "  distbuild      attaches to a distributed build (experimental)",
        "  doctor         debug and fix issues of Buck commands",
        "  fetch          downloads remote resources to your local machine",
        "  fix            attempts to fix errors encountered in the previous build",
        "  help           "
            + "shows this screen (or the help page of the specified command) and exits.",
        "  install        builds and installs an application",
        "  kill           kill buckd for the current project",
        "  killall        kill all buckd processes",
        "  parser-cache   Load and save state of the parser cache",
        "  project        generates project configuration files for an IDE",
        "  publish        builds and publishes a library to a central repository",
        "  query          "
            + "provides facilities to query information about the target nodes graph",
        "  rage           debug and fix issues of Buck commands",
        "  root           prints the absolute path to the root of the current buck project",
        "  run            runs a target as a command",
        "  server         query and control the http server",
        "  suggest        suggests a refactoring for the specified build target",
        "  targets        prints the list of buildable targets",
        "  test           builds and runs the tests for the specified target",
        "  uninstall      uninstalls an APK",
        "  verify-caches  Verify contents of internal Buck in-memory caches.",
        "",
        "Options:",
        " --flagfile FILE : File to read command line arguments from.",
        " --help (-h)     : Shows this screen and exits.",
        " --version (-V)  : Show version number.",
        "",
        "");
  }
}
