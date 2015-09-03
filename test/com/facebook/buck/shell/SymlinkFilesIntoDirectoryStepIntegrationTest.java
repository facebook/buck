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

package com.facebook.buck.shell;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SymlinkFilesIntoDirectoryStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * Verifies that {@link SymlinkFilesIntoDirectoryStep} works correctly by symlinking files at
   * various depth levels into an empty output directory.
   */
  @Test
  public void testSymlinkFilesIntoDirectory() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "symlink_files_into_directory", tmp);
    workspace.setUp();

    File outputFolder = tmp.newFolder("output");

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    SymlinkFilesIntoDirectoryStep symlinkStep = new SymlinkFilesIntoDirectoryStep(
        projectFilesystem,
        tmp.getRoot().toPath(),
        ImmutableSet.of(Paths.get("a.txt"), Paths.get("foo/b.txt"), Paths.get("foo/bar/c.txt")),
        outputFolder.toPath());
    int exitCode = symlinkStep.execute(executionContext);
    assertEquals(0, exitCode);

    // The remainder of the checks assert that we've created symlinks, which we may not have done
    // on certain operating systems.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    Path symlinkToADotTxt = new File(tmp.getRoot(), "output/a.txt").toPath();
    assertTrue(Files.isSymbolicLink(symlinkToADotTxt));
    assertEquals(projectFilesystem.resolve(Paths.get("a.txt")),
        Files.readSymbolicLink(symlinkToADotTxt));

    Path symlinkToBDotTxt = new File(tmp.getRoot(), "output/foo/b.txt").toPath();
    assertTrue(Files.isSymbolicLink(symlinkToBDotTxt));
    assertEquals(projectFilesystem.resolve(Paths.get("foo/b.txt")),
        Files.readSymbolicLink(symlinkToBDotTxt));

    Path symlinkToCDotTxt = new File(tmp.getRoot(), "output/foo/bar/c.txt").toPath();
    assertTrue(Files.isSymbolicLink(symlinkToCDotTxt));
    assertEquals(projectFilesystem.resolve(Paths.get("foo/bar/c.txt")),
        Files.readSymbolicLink(symlinkToCDotTxt));
  }
}
