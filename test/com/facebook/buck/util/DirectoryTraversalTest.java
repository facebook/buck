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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class DirectoryTraversalTest {
  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testDirectoryTraversalIgnorePaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "directory_traversal_ignore_paths", temporaryFolder);
    workspace.setUp();

    // Write a recursive symlink. We could store this in version control, but `ant lint` emits a
    // warning about the recursive symlink.
    Files.createDirectories(workspace.resolve(java.nio.file.Paths.get("loop/1")));
    Files.createSymbolicLink(
        workspace.resolve(java.nio.file.Paths.get("loop/1/upwards")),
        java.nio.file.Paths.get("../"));

    // If the user has a local.properties file, it'll get copied into the root of
    // `workspace`. We can't ignore it below (DirectoryTraversal()'s ignorePaths arg
    // is a set of *directory* paths) so we'll delete it here instead; we don't need
    // it for this test.
    Files.deleteIfExists(workspace.resolve(java.nio.file.Paths.get("local.properties")));

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
    // * | loop/
    // * | loop/1
    // - | loop/1/upwards   symlinks to ../
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
    new DirectoryTraversal(
        temporaryFolder.getRoot(),
        MorePaths.asPaths(ImmutableSet.of("a", "b/c", "loop", "loop/1"))) {
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
