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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuckQueryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // We can't have symlinks checked into the Buck repo, so we have to create the one we're using
    // for the test below here.
    workspace.move("symlinks/a/BUCK.disabled", "symlinks/a/BUCK");
    CreateSymlinksForTests.createSymLink(
        workspace.resolve("symlinks/a/inputs"),
        workspace.getDestPath().getFileSystem().getPath("real_inputs"));
  }

  /**
   * Tests for a bug where the combination of using instance equality for target nodes and using
   * multiple separate calls into the parse, each which invalidate the cache nodes with inputs under
   * symlinks, triggers a crash in `buck query` when it sees two instances of a node with the same
   * build target.
   */
  @Test
  public void testRdepsWithSymlinks() throws IOException {
    workspace.runBuckCommand("query", "rdeps(//symlinks/..., //symlinks/a:a)");
  }

  @Test
  public void testDependencyCycles() throws IOException {
    ProcessResult processResult = workspace.runBuckCommand("query", "deps(//cycles:a)");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("//cycles:a -> //cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:b)");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("//cycles:a -> //cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:c)");
    processResult.assertFailure();

    assertThat(processResult.getStderr(), containsString("//cycles:c -> //cycles:d -> //cycles:c"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:d)");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("//cycles:d -> //cycles:c -> //cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:e)");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("//cycles:c -> //cycles:d -> //cycles:c"));

    processResult = workspace.runBuckCommand("query", "deps(set(//cycles:f //cycles/dir:g))");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "//cycles:f -> //cycles/dir:g -> //cycles:h -> " + "//cycles/dir:i -> //cycles:f"));
  }
}
