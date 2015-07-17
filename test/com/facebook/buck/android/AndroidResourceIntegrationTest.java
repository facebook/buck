/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;

public class AndroidResourceIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_resource", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testOnlyUsesFirstOrderResources() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//res1:res");
    result.assertFailure();
    assertTrue(result.getStderr().contains("The following resources were not found"));
    assertTrue(result.getStderr().contains("another_name"));
    workspace.replaceFileContents("res1/BUCK", "#EXTRA_DEP_HERE", "'//res3:res',");
    workspace.runBuckBuild("//res1:res").assertSuccess();
  }

  @Test
  public void testGeneratedResourceDirectory() throws IOException, InterruptedException {

    // Verify we correctly build the R.txt file using a generated input resource directory.
    workspace.runBuckBuild("//generated_res:res").assertSuccess();
    String output =
        Splitter.on(' ').trimResults().splitToList(
            workspace.runBuckCommand("targets", "--show-output", "//generated_res:res")
                .assertSuccess()
                .getStdout()).get(1);
    assertThat(
        Files.readAllLines(workspace.getPath(output), Charsets.UTF_8),
        Matchers.contains(
            "int string another_name 0x7f010002",
            "int string some_name 0x7f010001"));

    // Add a new item in the input and verify that the resource rule gets re-run.
    Files.createDirectory(workspace.getPath("generated_res/input_res/raw"));
    workspace.writeContentsToPath("", "generated_res/input_res/raw/empty.txt");
    workspace.runBuckBuild("//generated_res:res").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//generated_res:res");
  }

}
