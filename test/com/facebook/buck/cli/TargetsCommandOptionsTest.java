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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.TargetsCommandOptions.ReferencedFiles;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TargetsCommandOptionsTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testGetCanonicalFilesUnderProjectRoot() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "target_command_options", tmp).setUp();

    Path projectRoot = tmp.getRootPath();
    ImmutableSet<String> nonCanonicalFilePaths = ImmutableSet.of(
        "src/com/facebook/CanonicalRelativePath.txt",
        "./src/com/otherpackage/.././/facebook/NonCanonicalPath.txt",
        projectRoot + "/ProjectRoot/src/com/facebook/AbsolutePath.txt",
        projectRoot + "/ProjectRoot/../PathNotUnderProjectRoot.txt");

    ReferencedFiles referencedFiles = TargetsCommandOptions.getCanonicalFilesUnderProjectRoot(
        projectRoot.resolve("ProjectRoot"),
        nonCanonicalFilePaths);
    assertEquals(
        ImmutableSet.of(
            Paths.get("src/com/facebook/CanonicalRelativePath.txt"),
            Paths.get("src/com/facebook/NonCanonicalPath.txt"),
            Paths.get("src/com/facebook/AbsolutePath.txt")),
        referencedFiles.relativePathsUnderProjectRoot);
    assertEquals(
        ImmutableSet.of(
            projectRoot.resolve("PathNotUnderProjectRoot.txt").toRealPath()),
        referencedFiles.absolutePathsOutsideProjectRoot);
  }
}
