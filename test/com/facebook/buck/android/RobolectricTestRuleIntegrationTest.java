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

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class RobolectricTestRuleIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Test
  public void testRobolectricTestBuildsWithDummyR() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder, true);
    workspace.setUp();
    workspace.addBuckConfigLocalOption(
        "test",
        "robolectric_location",
        Paths.get(ProjectWorkspace.TEST_CELL_LOCATION).toAbsolutePath().toString());
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInArgument()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder, true);
    workspace.setUp();
    workspace.addBuckConfigLocalOption(
        "test",
        "external_runner",
        tmpFolder.getRoot().resolve("test_runner.py").toAbsolutePath().toString());
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInFile()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder, true);
    workspace.setUp();
    workspace.addBuckConfigLocalOption(
        "test",
        "external_runner",
        tmpFolder.getRoot().resolve("test_runner.py").toAbsolutePath().toString());
    workspace.addBuckConfigLocalOption("test", "pass_robolectric_directories_in_file", "true");
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }
}
