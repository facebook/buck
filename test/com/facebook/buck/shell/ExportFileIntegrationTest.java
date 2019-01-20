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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class ExportFileIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void exportFileWillPopulateDepsCorrectlyWhenSourceParameterIsASourcePath()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "export_file_source_path_dep", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:file").assertSuccess();
  }

  @Test
  public void exportFileWorksWithDirectories() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "export_file_dir", tmp);
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:dir");
    assertTrue(Files.exists(output.resolve("file.txt")));
  }

  @Test
  public void exportFileHandlesUnexpectedFileAtOutputDirectoryPath() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "export_file_source_path_dep", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-output", "//:file");
    // Without this early build, buck will just delete all of buck-out.
    workspace.runBuckBuild("//:magic").assertSuccess();

    result.assertSuccess();
    String output = result.getStdout();
    Path outputPath = workspace.getPath(output.split("\\s+")[1]);
    Path parent = outputPath.getParent();

    Path sibling = parent.resolveSibling("other");
    Files.createDirectories(parent.getParent());
    // Create the parent as a file to ensure export_file can overwrite it without failing.
    Files.write(parent, ImmutableList.of("some data"));
    // To ensure that something isn't just deleting all of buck-out, write a sibling file and verify
    // it exists after.
    Files.write(sibling, ImmutableList.of("some data"));

    workspace.runBuckBuild("//:file").assertSuccess();
    assertEquals("some data\n", workspace.getFileContents(sibling));
  }
}
