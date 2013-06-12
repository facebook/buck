/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class DirectoryTraversalTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testDirectoryTraversalIgnorePaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "directory_traversal_ignore_paths", temporaryFolder);
    workspace.setUp();

    // The workspace contains the following:
    //
    //   | path
    // --+-------------
    // i | a/
    // - | a/a_file
    //   | b/
    // * | b/b_file
    // i | b/c/
    // - | b/c/b_c_file
    //   | b/d/
    // * | b/d/b_d_file
    // * | file
    //
    // Only the files flagged by '*' should be visited, because the directories flagged by 'i' are
    // ignored.
    final ImmutableSet<String> expectedVisitedPaths = ImmutableSet.of(
        "b/b_file",
        "b/d/b_d_file",
        "file"
    );
    final ImmutableSet.Builder<String> visitedPaths = ImmutableSet.builder();
    new DirectoryTraversal(temporaryFolder.getRoot(), ImmutableSet.<String>of("a", "b/c")) {
      @Override
      public void visit(File file, String relativePath) {
        visitedPaths.add(relativePath);
      }
    }.traverse();
    assertEquals("Visited paths should match expected set",
        expectedVisitedPaths,
        visitedPaths.build());
  }
}
