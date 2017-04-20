/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class WindowsCxxIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;


  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.WINDOWS);
    WindowsUtils.checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "win_x64", tmp);
    workspace.setUp();
    WindowsUtils.setUpWorkspace(workspace, "xplat");
  }

  @Test
  public void simpleBinary64() throws IOException {
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "run",
            "//app:hello#windows-x86_64");
    runResult.assertSuccess();
    assertThat(
        runResult.getStdout(),
        Matchers.containsString("The process is 64bits"));
    assertThat(
        runResult.getStdout(),
        Matchers.not(Matchers.containsString("The process is WOW64")));
  }

  @Test
  public void simpleBinaryWithLib() throws IOException {
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "run",
            "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(
        runResult.getStdout(),
        Matchers.containsString("BUCK ON WINDOWS"));
  }

  @Test
  public void simpleBinaryIsExecutableByCmd() throws IOException {
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "build",
            "//app:log");
    runResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app/log/log.txt");
    assertThat(
        workspace.getFileContents(outputPath),
        Matchers.containsString("The process is 64bits"));
  }


}
