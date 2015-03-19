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

package com.facebook.buck.d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class DBinaryIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void compileAndRun() throws Exception {
    assumeDCompilerAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(
        workspace.resolve("buck-out/bin/xyzzy/xyzzy").toString());
    assertEquals(0, result.getExitCode());
    assertEquals("Nothing happens.\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }

  private void assumeDCompilerAvailable() throws InterruptedException, IOException {
    Throwable exception = null;
    try {
      new DBuckConfig(new FakeBuckConfig()).getDCompiler();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}
