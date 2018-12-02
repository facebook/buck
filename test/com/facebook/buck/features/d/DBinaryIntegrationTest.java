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

package com.facebook.buck.features.d;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DBinaryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void cxx() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cxx", tmp);
    workspace.setUp();

    workspace.runBuckBuild("-v", "10", "//:test").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:test");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace
                .resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance("//:test")
                            .withFlavors(DBinaryDescription.BINARY_FLAVOR),
                        "%s/test"))
                .toString());
    assertEquals(0, result.getExitCode());
    assertEquals("1 + 1 = 2\n100 + 1 = 5\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }

  @Test
  public void xyzzy() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("-v", "10", "//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace
                .resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance("//:xyzzy")
                            .withFlavors(DBinaryDescription.BINARY_FLAVOR),
                        "%s/xyzzy"))
                .toString());
    assertEquals(0, result.getExitCode());
    assertEquals("Nothing happens.\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }
}
