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

package com.facebook.buck.go;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;

public class GoBinaryIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureGoIsAvailable() throws IOException, InterruptedException {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Test
  public void simpleBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void buildAfterChangeWorks() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    workspace.writeContentsToPath(
        workspace.getFileContents("main.go") + "// this is a comment",
        "main.go");
    workspace.runBuckBuild("//:xyzzy").assertSuccess();
  }

  @Test
  public void binaryWithLibrary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void nonGoLibraryDepErrors() throws IOException, InterruptedException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.containsString("is not an instance of go_library"));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckCommand("run", "//:illegal_dep").assertFailure();
  }
}
