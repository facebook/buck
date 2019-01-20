/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertFalse;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class MergeAssetsIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_multi_cell_resource", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testNoRelativePathsInOutputWithoutEmbeddedCells() throws Exception {
    workspace.runBuckCommand(workspace.getPath("home"), "build", ":list_outputs").assertSuccess();
    String unzipOutput =
        workspace.getFileContents("home/buck-out/gen/list_outputs/list_of_outputs.txt");
    for (String line : unzipOutput.split("\n")) {
      assertFalse(line.contains(".."));
    }
  }

  @Test
  public void testNoRelativePathsInOutputWithEmbeddedCells() throws Exception {
    workspace
        .runBuckCommand(
            workspace.getPath("home"),
            "build",
            ":list_outputs",
            "--config",
            "project.embedded_cell_buck_out_enabled=true")
        .assertSuccess();
    String unzipOutput =
        workspace.getFileContents("home/buck-out/gen/list_outputs/list_of_outputs.txt");
    for (String line : unzipOutput.split("\n")) {
      assertFalse(
          String.format(
              "Apk entries should only contain normalized paths (Found '%s')", line.trim()),
          line.contains(".."));
    }
  }
}
