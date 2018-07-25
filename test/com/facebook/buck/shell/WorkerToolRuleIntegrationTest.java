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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkerToolRuleIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "worker_tool_test", tmpFolder);
    workspace.setUp();
  }

  /**
   * This test builds three genrules simultaneously which each use a worker macro. //:test1 and
   * //:test2 both reference the same worker_tool, so they will both communicate with the same
   * external process, while //:test3 will communicate with a separate process since it references a
   * separate worker_tool.
   *
   * @throws IOException
   */
  @Test
  public void testGenrulesThatUseWorkerMacros() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target1 = workspace.newBuildTarget("//:test1");
    BuildTarget target2 = workspace.newBuildTarget("//:test2");
    BuildTarget target3 = workspace.newBuildTarget("//:test3");

    workspace
        .runBuckBuild(
            target1.getFullyQualifiedName(),
            target2.getFullyQualifiedName(),
            target3.getFullyQualifiedName())
        .assertSuccess();
    workspace.verify(
        Paths.get("test1_output.expected"), BuildTargetPaths.getGenPath(filesystem, target1, "%s"));
    workspace.verify(
        Paths.get("test2_output.expected"), BuildTargetPaths.getGenPath(filesystem, target2, "%s"));
    workspace.verify(
        Paths.get("test3_output.expected"), BuildTargetPaths.getGenPath(filesystem, target3, "%s"));
  }

  /**
   * This test builds two genrules simultaneously. //:test4 and //:test5 both reference the same
   * worker_tool, with `max_workers` set to unlimited. They will both communicate with their own
   * separate external process, started up with the same command line.
   */
  @Test
  public void testGenrulesThatUseWorkerMacrosWithConcurrency() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target1 = workspace.newBuildTarget("//:test4");
    BuildTarget target2 = workspace.newBuildTarget("//:test5");

    workspace
        .runBuckBuild(target1.getFullyQualifiedName(), target2.getFullyQualifiedName())
        .assertSuccess();

    String contents =
        workspace.getFileContents(BuildTargetPaths.getGenPath(filesystem, target1, "%s/output.txt"))
            + workspace.getFileContents(
                BuildTargetPaths.getGenPath(filesystem, target2, "%s/output.txt"));
    ImmutableSet<String> processIDs = ImmutableSet.copyOf(contents.trim().split("\\s+"));
    assertThat(processIDs.size(), Matchers.equalTo(2));
  }

  @Test
  public void testPersistentWorkerToolRules() throws Exception {
    BuildTarget target1 = workspace.newBuildTarget("//:test6");
    BuildTarget target2 = workspace.newBuildTarget("//:test7");

    workspace
        .runBuckBuild(target1.getFullyQualifiedName(), target2.getFullyQualifiedName())
        .assertSuccess();
  }

  @Test
  public void testPersistentWorkerToolReusesProcess() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target1 = workspace.newBuildTarget("//:test6");
    String fullyQualifiedName = target1.getFullyQualifiedName();

    workspace.runBuckdCommand("build", fullyQualifiedName).assertSuccess();
    String contents =
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(filesystem, target1, "%s/output.txt"));
    workspace.replaceFileContents("test6.input", "1", "2");
    workspace.runBuckdCommand("build", fullyQualifiedName).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(fullyQualifiedName);
    contents +=
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(filesystem, target1, "%s/output.txt"));

    ImmutableSet<String> processIDs = ImmutableSet.copyOf(contents.trim().split("\\s+"));
    assertThat(processIDs.size(), Matchers.equalTo(1));
  }

  @Test
  public void testPersistentWorkerToolReusesProcessOnlyIfUnchanged() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target1 = workspace.newBuildTarget("//:test6");
    String fullyQualifiedName = target1.getFullyQualifiedName();

    workspace.runBuckdCommand("build", fullyQualifiedName).assertSuccess();
    String contents =
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(filesystem, target1, "%s/output.txt"));
    workspace.replaceFileContents("test6.input", "1", "2");
    workspace.replaceFileContents("concurrent_tool.sh", "sleep 1", "sleep 2");

    workspace.runBuckdCommand("build", fullyQualifiedName).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(fullyQualifiedName);
    contents +=
        workspace.getFileContents(
            BuildTargetPaths.getGenPath(filesystem, target1, "%s/output.txt"));

    ImmutableSet<String> processIDs = ImmutableSet.copyOf(contents.trim().split("\\s+"));
    assertThat(processIDs.size(), Matchers.equalTo(2));
  }

  @Test
  public void testWorkerToolArgs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target1 = workspace.newBuildTarget("//:test8");
    BuildTarget target2 = workspace.newBuildTarget("//:test9");

    workspace
        .runBuckBuild(target1.getFullyQualifiedName(), target2.getFullyQualifiedName())
        .assertSuccess();
    workspace.verify(
        Paths.get("test8_output.expected"), BuildTargetPaths.getGenPath(filesystem, target1, "%s"));
    workspace.verify(
        Paths.get("test9_output.expected"), BuildTargetPaths.getGenPath(filesystem, target2, "%s"));
  }

  @Test
  public void testWorkerCrashDoesNotHang() throws Exception {
    workspace.runBuckBuild("//:test10").assertFailure();
  }
}
