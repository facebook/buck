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

public class AndroidXmlFileIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  private static final String MAIN_BUILD_TARGET =
      "//java/com/sample/lib:lib";
  private static final String PATH_TO_STRINGS_XML = "res/com/sample/base/res/values/strings.xml";
  private static final String PATH_TO_LAYOUT_XML = "res/com/sample/top/res/layout/top_layout.xml";

  @Before
  public void setUp() throws IOException {
    tmpFolder.create();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);

    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();
  }

  @Test
  public void testEditingStringOnlyBuildsResourceRule() throws IOException {
    workspace.replaceFileContents(PATH_TO_STRINGS_XML, "Hello", "Bye");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally("//res/com/sample/base:base");
    buildLog.assertTargetHadMatchingDepsAbi("//res/com/sample/top:top");
    buildLog.assertTargetHadMatchingDepsAbi("//java/com/sample/lib:lib#dummy_r_dot_java");
    buildLog.assertTargetHadMatchingDepsAbi(MAIN_BUILD_TARGET);
  }

  @Test
  public void testEditingColorOnlyBuildsResourceRule() throws IOException {
    workspace.replaceFileContents(PATH_TO_LAYOUT_XML, "white", "black");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetHadMatchingRuleKey("//res/com/sample/base:base");
    buildLog.assertTargetBuiltLocally("//res/com/sample/top:top");
    buildLog.assertTargetHadMatchingDepsAbi("//java/com/sample/lib:lib#dummy_r_dot_java");
    buildLog.assertTargetHadMatchingDepsAbi(MAIN_BUILD_TARGET);
  }

  @Test
  public void testAddingAStringBuildsAllRules() throws IOException {
    workspace.replaceFileContents(PATH_TO_STRINGS_XML,
        "</resources>",
        "<string name=\"base_text\">Goodbye!</string></resources>");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally("//res/com/sample/base:base");
    buildLog.assertTargetBuiltLocally("//res/com/sample/top:top");
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dummy_r_dot_java");
    buildLog.assertTargetBuiltLocally(MAIN_BUILD_TARGET);
  }

  @Test
  public void testRenamingAStringBuildsAllRules() throws IOException {
    workspace.replaceFileContents(PATH_TO_STRINGS_XML, "base_button", "base_text");
    workspace.replaceFileContents(PATH_TO_LAYOUT_XML, "base_button", "base_text");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally("//res/com/sample/base:base");
    buildLog.assertTargetBuiltLocally("//res/com/sample/top:top");
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dummy_r_dot_java");
    buildLog.assertTargetBuiltLocally(MAIN_BUILD_TARGET);
  }

  @Test
  public void testTransitiveResourceRuleAbi() throws IOException {
    workspace.replaceFileContents(PATH_TO_STRINGS_XML,
        "</resources>",
        "<string name=\"base_text\">Goodbye!</string></resources>");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//java/com/sample/lib:lib_using_transitive_empty_res");

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib_using_transitive_empty_res");
    buildLog.assertTargetBuiltLocally(
        "//java/com/sample/lib:lib_using_transitive_empty_res#dummy_r_dot_java");
  }
}
