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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

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
    String stringsXmlFileContents = workspace.getFileContents(PATH_TO_STRINGS_XML);
    stringsXmlFileContents = stringsXmlFileContents.replace("Hello", "Bye");
    workspace.writeContentsToPath(stringsXmlFileContents, PATH_TO_STRINGS_XML);

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();

    assertEquals(
        BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
        successTypes.get(BuildTargetFactory.newInstance(MAIN_BUILD_TARGET)).get());
    assertEquals(
        BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
        successTypes.get(
            BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dummy_r_dot_java")).get());
    assertEquals(
        BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/top:top")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/base:base")).get());
  }

  @Test
  public void testEditingColorOnlyBuildsResourceRule() throws IOException {
    String layoutXmlFileContents = workspace.getFileContents(PATH_TO_LAYOUT_XML);
    layoutXmlFileContents = layoutXmlFileContents.replace("white", "black");
    workspace.writeContentsToPath(layoutXmlFileContents, PATH_TO_LAYOUT_XML);

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", MAIN_BUILD_TARGET);

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();
    assertEquals(
        BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
        successTypes.get(BuildTargetFactory.newInstance(MAIN_BUILD_TARGET)).get());
    assertEquals(
        BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
        successTypes.get(
            BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dummy_r_dot_java")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/top:top")).get());
    assertEquals(
        BuildRuleSuccess.Type.MATCHING_RULE_KEY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/base:base")).get());
  }

  @Test
  public void testAddingAStringBuildsAllRules() throws IOException {
    String stringsXmlFileContents = workspace.getFileContents(PATH_TO_STRINGS_XML);
    stringsXmlFileContents = stringsXmlFileContents.replace(
        "</resources>",
        "<string name=\"base_text\">Goodbye!</string></resources>");
    workspace.writeContentsToPath(stringsXmlFileContents, PATH_TO_STRINGS_XML);

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", MAIN_BUILD_TARGET);

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance(MAIN_BUILD_TARGET)).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(
            BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dummy_r_dot_java")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/top:top")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/base:base")).get());
  }

  @Test
  public void testRenamingAStringBuildsAllRules() throws IOException {
    String stringsXmlFileContents = workspace.getFileContents(PATH_TO_STRINGS_XML);
    stringsXmlFileContents = stringsXmlFileContents.replace("base_button", "base_text");
    workspace.writeContentsToPath(stringsXmlFileContents, PATH_TO_STRINGS_XML);
    String layoutXmlFileContents = workspace.getFileContents(PATH_TO_LAYOUT_XML);
    layoutXmlFileContents = layoutXmlFileContents.replace("base_button", "base_text");
    workspace.writeContentsToPath(layoutXmlFileContents, PATH_TO_LAYOUT_XML);

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance(MAIN_BUILD_TARGET)).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(
            BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dummy_r_dot_java")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/top:top")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance("//res/com/sample/base:base")).get());
  }

  @Test
  public void testTransitiveResourceRuleAbi() throws IOException {
    String stringsXmlFileContents = workspace.getFileContents(PATH_TO_STRINGS_XML);
    stringsXmlFileContents = stringsXmlFileContents.replace(
        "</resources>",
        "<string name=\"base_text\">Goodbye!</string></resources>");
    workspace.writeContentsToPath(stringsXmlFileContents, PATH_TO_STRINGS_XML);

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//java/com/sample/lib:lib_using_transitive_empty_res");

    Map<BuildTarget, Optional<BuildRuleSuccess.Type>> successTypes =
        workspace.getBuildRuleSuccessTypes();
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(BuildTargetFactory.newInstance(
                "//java/com/sample/lib:lib_using_transitive_empty_res")).get());
    assertEquals(
        BuildRuleSuccess.Type.BUILT_LOCALLY,
        successTypes.get(
            BuildTargetFactory.newInstance(
                "//java/com/sample/lib:lib_using_transitive_empty_res#dummy_r_dot_java")).get());
  }
}
