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

public class UberRDotJavaAbiIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    tmpFolder.create();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();
  }

  @Test
  public void testEditingColorDoesNotRebuildUberRDotJava() throws IOException {
    workspace.replaceFileContents("res/com/sample/top/res/layout/top_layout.xml", "white", "black");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingDepsAbi("//apps/sample:app#uber_r_dot_java");
  }

  @Test
  public void testAddingAResourceForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/res/values/strings.xml",
        "</resources>",
        "<string name=\"base_text\">Goodbye!</string></resources>");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/sample:app#uber_r_dot_java");
  }
}
