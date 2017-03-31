/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import static org.junit.Assert.assertFalse;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class ProjectIntegrationTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testAndroidLibraryProject() throws IOException {
    runBuckProjectAndVerify("android_library");
  }

  @Test
  public void testVersion2BuckProject() throws IOException {
    runBuckProjectAndVerify("experimental_project1");
  }

  @Test
  public void testVersion2BuckProjectWithoutAutogeneratingSources() throws IOException {
    runBuckProjectAndVerify("experimental_project_without_autogeneration");
  }

  @Test
  public void testVersion2BuckProjectSlice() throws IOException {
    runBuckProjectAndVerify(
        "experimental_project_slice",
        "--without-tests",
        "modules/dep1:dep1");
  }

  @Test
  public void testVersion2BuckProjectSourceMerging() throws IOException {
    runBuckProjectAndVerify("experimental_project_source_merge", "//java/code/modules/tip");
  }

  @Test
  public void testBuckProjectWithCustomAndroidSdks() throws IOException {
    runBuckProjectAndVerify("project_with_custom_android_sdks");
  }

  @Test
  public void testBuckProjectWithCustomJavaSdks() throws IOException {
    runBuckProjectAndVerify("project_with_custom_java_sdks");
  }

  @Test
  public void testBuckProjectWithIntellijSdk() throws IOException {
    runBuckProjectAndVerify("project_with_intellij_sdk");
  }

  @Test
  public void testVersion2BuckProjectWithProjectSettings() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_project_settings");
  }

  @Test
  public void testVersion2BuckProjectWithScripts() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_scripts", "//modules/dep1:dep1");
  }

  @Test
  public void testVersion2BuckProjectWithUnusedLibraries() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "experimental_project_with_unused_libraries", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess("buck project should exit cleanly");

    assertFalse(workspace.resolve(".idea/libraries/library_libs_jsr305.xml").toFile().exists());
  }

  @Test
  public void testVersion2BuckProjectWithExcludedResources() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_excluded_resources");
  }

  @Test
  public void testVersion2BuckProjectWithAssets() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_assets");
  }

  @Test
  public void testVersion2BuckProjectWithLanguageLevel() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_language_level");
  }

  @Test
  public void testVersion2BuckProjectWithGeneratedSources() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_generated_sources");
  }

  @Test
  public void testBuckProjectWithSubdirGlobResources() throws IOException {
    runBuckProjectAndVerify("project_with_subdir_glob_resources");
  }

  @Test
  public void testRobolectricTestRule() throws IOException {
    runBuckProjectAndVerify("robolectric_test");
  }

  private ProcessResult runBuckProjectAndVerify(
      String folderWithTestData,
      String... commandArgs) throws IOException {
    return ProjectIntegrationTestUtils.runBuckProject(
        this,
        temporaryFolder,
        folderWithTestData,
        true,
        commandArgs);
  }
}
