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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class RunCommandIntegrationTest extends EasyMockSupport {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testRunCommandWithArguments() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "run-command",
        temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "run",
        "//cmd:command",
        "one_arg",
        workspace.getFile("output").toPath().toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    System.out.print(result.getStdout());
    workspace.verify();
  }

  @Test
  public void testRunCommandFailure() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "run-command-failure",
        temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//cmd:command");
    result.assertSpecialExitCode("buck run should propagate failure", 5);
  }

}
