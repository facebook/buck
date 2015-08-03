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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NumThreadsIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testCommandLineNumThreadsArgOverridesBuckConfig() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "num_threads", tmp);
    workspace.setUp();

    ProcessResult buildResult1 = workspace.runBuckCommand(
        "build", "//:noop", "--verbose", "10");
    assertThat("Number of threads to use should be read from .buckconfig.",
        buildResult1.getStderr(),
        containsString("Creating a build with 7 threads.\n"));

    ProcessResult buildResult2 = workspace.runBuckCommand(
        "build", "//:noop", "--verbose", "10", "--num-threads", "27");
    assertThat(
        "Command-line arg should override value in .buckconfig.",
        buildResult2.getStderr(),
        containsString("Creating a build with 27 threads.\n"));

    Path buckconfig = workspace.getPath(".buckconfig");
    Files.delete(buckconfig);

    int numThreads = (int) (Runtime.getRuntime().availableProcessors() * 1.25);
    ProcessResult buildResult3 = workspace.runBuckCommand(
        "build", "//:noop", "--verbose", "10");
    assertThat(
        "Once .buckconfig is deleted, the number of threads should be " +
        "a function of the number of processors.",
        buildResult3.getStderr(),
        containsString("Creating a build with " + numThreads + " threads.\n"));
  }

  @Test
  public void testBuckProjectUsesNumThreadsInBuckConfigFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "num_threads", tmp);
    workspace.setUp();

    ProcessResult buildResult1 = workspace.runBuckCommand("project", "--verbose", "10");
    assertThat("Number of threads to use should be read from .buckconfig.",
        buildResult1.getStderr(),
        containsString("Creating a build with 7 threads.\n"));

    String newBuckProject = Joiner.on('\n').join(
        "[project]",
        "  initial_targets = //:noop");
    Files.write(workspace.getPath(".buckconfig"), newBuckProject.getBytes(UTF_8));
    int numThreads = (int) (Runtime.getRuntime().availableProcessors() * 1.25);
    ProcessResult buildResult2 = workspace.runBuckCommand("project", "--verbose", "10");
    assertThat(
        "Once num_threads is removed from .buckconfig, the number of threads should be " +
        "a function of the number of processors.",
        buildResult2.getStderr(),
        containsString("Creating a build with " + numThreads + " threads.\n"));
  }
}
