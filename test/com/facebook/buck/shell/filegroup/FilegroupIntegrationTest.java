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

package com.facebook.buck.shell.filegroup;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class FilegroupIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testDirectoryStructureIsKept() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "filegroup", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "--show-output", "//test/:dir_txt");

    result.assertSuccess();

    String output = result.getStdout();
    Path outputPath = workspace.getPath(output.split("\\s+")[1]);

    assertEquals("file", workspace.getFileContents(outputPath.resolve("dir").resolve("file.txt")));
    assertEquals(
        "another_file",
        workspace.getFileContents(
            outputPath.resolve("dir").resolve("subdir").resolve("another_file.txt")));
  }

  @Test
  public void testGenruleOutputIsCopied() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "filegroup", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("build", "--show-output", "//test/:dir_with_genrule");

    result.assertSuccess();

    String output = result.getStdout();
    Path outputPath = workspace.getPath(output.split("\\s+")[1]);

    assertEquals("file", workspace.getFileContents(outputPath.resolve("dir").resolve("file.txt")));
    assertEquals(
        "another_file",
        workspace.getFileContents(
            outputPath.resolve("dir").resolve("subdir").resolve("another_file.txt")));
    assertEquals(
        "generated",
        workspace
            .getFileContents(outputPath.resolve("generated_dir").resolve("generated_file.txt"))
            .trim());
  }
}
