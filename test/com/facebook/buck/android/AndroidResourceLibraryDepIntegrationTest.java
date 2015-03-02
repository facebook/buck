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

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidResourceLibraryDepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testModifyingLibraryDependencyDoesNotCauseRebuilt() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    String appTarget = "//apps/sample:app_res_lib_dep";
    String resTarget = "//res/com/sample/base:base_with_lib_dep";
    String libTarget = "//java/com/sample/small:small";

    // Do the initial resource build.
    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand("build", appTarget);
    first.assertSuccess();

    // Verify that the resource and library dependency were successfully built.
    BuckBuildLog firstBuildLog = workspace.getBuildLog();
    firstBuildLog.assertTargetBuiltLocally(appTarget);
    firstBuildLog.assertTargetBuiltLocally(resTarget);
    firstBuildLog.assertTargetBuiltLocally(libTarget);

    // Update the java library dependency, which will force it to be rebuilt.
    workspace.replaceFileContents(
        "java/com/sample/small/Sample.java",
        "savedInstanceState",
        "savedInstanceState2");

    workspace.resetBuildLogFile();

    // Re-run the build, which should just rebuild the java library.
    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand("build", appTarget);
    second.assertSuccess();

    // Now verify that just the library and top-level binary got rebuilt.
    BuckBuildLog secondBuildLog = workspace.getBuildLog();
    secondBuildLog.assertTargetBuiltLocally(appTarget);
    secondBuildLog.assertTargetHadMatchingRuleKey(resTarget);
    secondBuildLog.assertTargetBuiltLocally(libTarget);
  }

}
