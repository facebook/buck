/*
 * Copyright 2018-present Facebook, Inc.
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
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class HelpCommandIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testHelpCommandFinishedSuccessfully() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_project", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("help");

    result.assertSuccess(
        "Description:\n"
            + "  Buck build tool\n"
            + "\n"
            + "Usage:\n"
            + "  buck [<options>]\n"
            + "  buck <command> --help\n"
            + "  buck <command> [<command-options>]\n"
            + "\n"
            + "Available commands:\n"
            + "  audit          lists the inputs for the specified target\n"
            + "  build          builds the specified target\n"
            + "  cache          makes calls to the artifact cache\n"
            + "  cachedelete    Delete artifacts from the local and remote cache\n"
            + "  clean          deletes any generated files and caches\n"
            + "  distbuild      attaches to a distributed build (experimental)\n"
            + "  doctor         debug and fix issues of Buck commands\n"
            + "  fetch          downloads remote resources to your local machine\n"
            + "  fix            attempts to fix errors encountered in the previous build\n"
            + "  help           shows this screen (or the help page of the specified command) and exits.\n"
            + "  install        builds and installs an application\n"
            + "  kill           kill buckd for the current project\n"
            + "  killall        kill all buckd processes\n"
            + "  machoutils     provides some utils for Mach O binary files\n"
            + "  parser-cache   Load and save state of the parser cache\n"
            + "  project        generates project configuration files for an IDE\n"
            + "  publish        builds and publishes a library to a central repository\n"
            + "  query          provides facilities to query information about the target nodes graph\n"
            + "  rage           debug and fix issues of Buck commands\n"
            + "  root           prints the absolute path to the root of the current buck project\n"
            + "  run            runs a target as a command\n"
            + "  server         query and control the http server\n"
            + "  suggest        suggests a refactoring for the specified build target\n"
            + "  targets        prints the list of buildable targets\n"
            + "  test           builds and runs the tests for the specified target\n"
            + "  uninstall      uninstalls an APK\n"
            + "  verify-caches  Verify contents of internal Buck in-memory caches.\n"
            + "\n"
            + "Options:\n"
            + " --flagfile FILE : File to read command line arguments from.\n"
            + " --help (-h)     : Shows this screen and exits.\n"
            + " --version (-V)  : Show version number.");
  }
}
