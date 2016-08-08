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

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;

public class BuckQueryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    // We can't have symlinks checked into the Buck repo, so we have to create the one we're using
    // for the test below here.
    workspace.move("symlinks/a/BUCK.disabled", "symlinks/a/BUCK");
    Files.createSymbolicLink(
        workspace.resolve("symlinks/a/inputs"),
        workspace.getDestPath().getFileSystem().getPath("real_inputs"));
  }

  /**
   * Tests for a bug where the combination of using instance equality for target nodes and using
   * multiple separate calls into the parse, each which invalidate the cache nodes with inputs
   * under symlinks, triggers a crash in `buck query` when it sees two instances of a node with
   * the same build target.
   */
  @Test
  public void testRdepsWithSymlinks() throws IOException {
    workspace.runBuckCommand("query", "rdeps(//symlinks/..., //symlinks/a:a)");
  }

}
