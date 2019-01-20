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

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryCrossCellIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Test
  public void testCrossRepositoryDexMerge() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_binary_cross_cell_test", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);

    workspace.runBuckCommand("build", "//:app", "other_repo//:app").assertSuccess();
  }

  @Test
  public void testCrossRepositoryDexMergeWithSplitDex() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_binary_cross_cell_test", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);

    workspace
        .runBuckCommand("build", "//:app_with_main_lib", "other_repo//:app_with_mainlib")
        .assertSuccess();
  }

  @Test
  public void testBuildingBinariesSeparately() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_binary_cross_cell_test", tmpFolder);
    workspace.setUp();
    workspace.enableDirCache();
    setWorkspaceCompilationMode(workspace);

    workspace.runBuckCommand("build", "other_repo//:app_with_mainlib").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", "//:app_with_main_lib").assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//java/com/sample/mainlib:mainlib");
  }
}
