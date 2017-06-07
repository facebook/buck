/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

public class RunCommandIntegrationTest extends EasyMockSupport {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testRunCommandWithNoArguments() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run");

    result.assertFailure();
    assertThat(result.getStderr(), containsString("buck run <target> <arg1> <arg2>..."));
    assertThat(result.getStderr(), containsString("No target given to run"));
  }

  @Test
  public void testRunCommandWithNonExistentTarget() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//does/not/exist");

    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsString(
            "No build file at does/not/exist/BUCK when resolving target //does/not/exist:exist."));
  }

  @Test
  public void testRunCommandWithArguments() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "run",
            "//cmd:command",
            "one_arg",
            workspace.getPath("output").toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    assertEquals("SUCCESS\n", result.getStdout());
    workspace.verify();
  }

  @Test
  public void testRunCommandWithDashArguments() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "run",
            "//cmd:command",
            "--",
            "one_arg",
            workspace.getPath("output").toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    assertThat(result.getStdout(), containsString("SUCCESS"));
    workspace.verify();
  }

  @Test
  public void testRunCommandFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "run-command-failure", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//cmd:command");
    result.assertSpecialExitCode("buck run should propagate failure", 5);
  }
}
