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

import static org.junit.Assert.fail;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
  }

  /**
   * Tests for a bug where the combination of using instance equality for target nodes and using
   * multiple separate calls into the parse, each which invalidate the cache nodes with inputs under
   * symlinks, triggers a crash in `buck query` when it sees two instances of a node with the same
   * build target.
   */
  @Test
  public void testRdepsWithSymlinks() throws Exception {
    // We can't have symlinks checked into the Buck repo, so we have to create the one we're using
    // for the test below here.
    workspace.move("symlinks/a/BUCK.disabled", "symlinks/a/BUCK");
    CreateSymlinksForTests.createSymLink(
        workspace.resolve("symlinks/a/inputs"),
        workspace.getDestPath().getFileSystem().getPath("real_inputs"));
    workspace.runBuckCommand("query", "rdeps(//symlinks/..., //symlinks/a:a)");
  }

  @Test
  public void testDependencyCycles() {
    ProcessResult processResult = workspace.runBuckCommand("query", "deps(//cycles:a)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:b)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:a"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:c)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:d)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(//cycles:e)");
    assertContainsCycle(processResult, ImmutableList.of("//cycles:c", "//cycles:d"));

    processResult = workspace.runBuckCommand("query", "deps(set(//cycles:f //cycles/dir:g))");
    assertContainsCycle(
        processResult,
        ImmutableList.of("//cycles:f", "//cycles/dir:g", "//cycles:h", "//cycles/dir:i"));
  }

  /**
   * Assert that the command failed and that the stderr message complains about a cycle with links
   * in the order specified by {@code chain}.
   */
  private static void assertContainsCycle(ProcessResult processResult, List<String> chain) {
    // Should have failed because graph contains a cycle.
    processResult.assertFailure();

    String stderr = processResult.getStderr();
    List<String> cycleCandidates = new ArrayList<>(chain.size());
    int chainSize = chain.size();
    Joiner joiner = Joiner.on(" -> ");
    for (int i = 0; i < chainSize; i++) {
      List<String> elements = new ArrayList<>(chain.size() + 1);
      for (int j = 0; j < chainSize; j++) {
        int index = (i + j) % chainSize;
        elements.add(chain.get(index));
      }
      elements.add(chain.get(i));
      String cycle = joiner.join(elements);
      if (stderr.contains(cycle)) {
        // Expected cycle string found!
        return;
      }
      cycleCandidates.add(cycle);
    }
    fail(stderr + " contained none of " + Joiner.on('\n').join(cycleCandidates));
  }
}
