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


import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class GenAidlIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void buildingCleaningAndThenRebuildingFromCacheShouldWorkAsExpected() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "cached_build",
        tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Populate the cache
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:android-lib");
    result.assertSuccess();
    result = workspace.runBuckCommand("clean");
    result.assertSuccess();

    // Now the cache is clean, do the build where we expect the results to come from the cache
    result = workspace.runBuckBuild("//:android-lib");
    result.assertSuccess();
  }
}
