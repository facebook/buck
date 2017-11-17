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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Lists;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class ProjectIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testAndroidLibraryProject() throws InterruptedException, IOException {
    runBuckProjectAndVerify("android_library");
  }

  @Test
  public void testAndroidBinaryProject() throws InterruptedException, IOException {
    runBuckProjectAndVerify("android_binary");
  }

  @Test
  public void testVersion2BuckProject() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project1");
  }

  @Test
  public void testVersion2BuckProjectWithoutAutogeneratingSources()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_without_autogeneration");
  }

  @Test
  public void testVersion2BuckProjectSlice() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_slice", "--without-tests", "modules/dep1:dep1");
  }

  @Test
  public void testVersion2BuckProjectSourceMerging() throws InterruptedException, IOException {
    runBuckProjectAndVerify("aggregation");
  }

  @Test
  public void testBuckProjectWithCustomAndroidSdks() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_custom_android_sdks");
  }

  @Test
  public void testBuckProjectWithCustomJavaSdks() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_custom_java_sdks");
  }

  @Test
  public void testBuckProjectWithIntellijSdk() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_intellij_sdk");
  }

  @Test
  public void testVersion2BuckProjectWithProjectSettings()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_project_settings");
  }

  @Test
  public void testVersion2BuckProjectWithScripts() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_scripts", "//modules/dep1:dep1");
  }

  @Test
  public void testVersion2BuckProjectWithUnusedLibraries() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_unused_libraries", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess("buck project should exit cleanly");

    assertFalse(workspace.resolve(".idea/libraries/library_libs_jsr305.xml").toFile().exists());
  }

  @Test
  public void testVersion2BuckProjectWithExcludedResources()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_excluded_resources");
  }

  @Test
  public void testVersion2BuckProjectWithAssets() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_assets");
  }

  @Test
  public void testVersion2BuckProjectWithLanguageLevel() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_language_level");
  }

  @Test
  public void testVersion2BuckProjectWithOutputUrl() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_output_url");
  }

  @Test
  public void testVersion2BuckProjectWithJavaResources() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_java_resources");
  }

  public void testVersion2BuckProjectWithExtraOutputModules()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_extra_output_modules");
  }

  @Test
  public void testVersion2BuckProjectWithGeneratedSources()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_generated_sources");
  }

  @Test
  public void testBuckProjectWithSubdirGlobResources() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_subdir_glob_resources");
  }

  @Test
  public void testRobolectricTestRule() throws InterruptedException, IOException {
    runBuckProjectAndVerify("robolectric_test");
  }

  @Test
  public void testAndroidResourcesInDependencies() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_android_resources");
  }

  @Test
  public void testPrebuiltJarWithJavadoc() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_prebuilt_jar");
  }

  @Test
  public void testZipFile() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_zipfile");
  }

  @Test
  public void testAndroidResourcesAndLibraryInTheSameFolder()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("android_resources_in_the_same_folder");
  }

  @Test
  public void testAndroidResourcesWithPackagesAtTheSameLocation()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_multiple_resources_with_package_names");
  }

  @Test
  public void testCxxLibrary() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_cxx_library");
  }

  @Test
  public void testAggregatingCxxLibrary() throws InterruptedException, IOException {
    runBuckProjectAndVerify("aggregation_with_cxx_library");
  }

  @Test
  public void testSavingGeneratedFilesList() throws InterruptedException, IOException {
    runBuckProjectAndVerify(
        "save_generated_files_list",
        "--file-with-list-of-generated-files",
        ".idea/generated-files.txt");
  }

  @Test
  public void testMultipleLibraries() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_multiple_libraries");
  }

  @Test
  public void testProjectWithIgnoredTargets() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_ignored_targets");
  }

  @Test
  public void testProjectWithCustomPackages() throws InterruptedException, IOException {
    runBuckProjectAndVerify("aggregation_with_custom_packages");
  }

  @Test
  public void testAndroidResourceAggregation() throws InterruptedException, IOException {
    runBuckProjectAndVerify("android_resource_aggregation");
  }

  @Test
  public void testAndroidResourceAggregationWithLimit() throws InterruptedException, IOException {
    runBuckProjectAndVerify("android_resource_aggregation_with_limit");
  }

  @Test
  public void testProjectIncludesTestsByDefault() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_tests_by_default", "//modules/lib:lib");
  }

  @Test
  public void testProjectExcludesTestsWhenRequested() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_without_tests", "--without-tests", "//modules/lib:lib");
  }

  @Test
  public void testProjectExcludesDepTestsWhenRequested() throws InterruptedException, IOException {
    runBuckProjectAndVerify(
        "project_without_dep_tests", "--without-dependencies-tests", "//modules/lib:lib");
  }

  @Test
  public void testUpdatingExistingWorkspace() throws InterruptedException, IOException {
    runBuckProjectAndVerify("update_existing_workspace");
  }

  @Test
  public void testCreateNewWorkspace() throws InterruptedException, IOException {
    runBuckProjectAndVerify("create_new_workspace");
  }

  @Test
  public void testUpdateMalformedWorkspace() throws InterruptedException, IOException {
    runBuckProjectAndVerify("update_malformed_workspace");
  }

  @Test
  public void testUpdateWorkspaceWithoutIgnoredNodes() throws InterruptedException, IOException {
    runBuckProjectAndVerify("update_workspace_without_ignored_nodes");
  }

  @Test
  public void testUpdateWorkspaceWithoutManagerNode() throws InterruptedException, IOException {
    runBuckProjectAndVerify("update_workspace_without_manager_node");
  }

  @Test
  public void testUpdateWorkspaceWithoutProjectNode() throws InterruptedException, IOException {
    runBuckProjectAndVerify("update_workspace_without_project_node");
  }

  @Test
  public void testProjectWthPackageBoundaryException() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_package_boundary_exception", "//project2:lib");
  }

  @Test
  public void testProjectWithProjectRoot() throws InterruptedException, IOException {
    runBuckProjectAndVerify(
        "project_with_project_root",
        "--intellij-project-root",
        "project1",
        "--intellij-include-transitive-dependencies",
        "--intellij-module-group-name",
        "",
        "//project1/lib:lib");
  }

  @Test
  public void testGeneratingAndroidManifest() throws InterruptedException, IOException {
    runBuckProjectAndVerify("generate_android_manifest");
  }

  @Test
  public void testPreprocessScript() throws InterruptedException, IOException {
    ProcessResult result = runBuckProjectAndVerify("preprocess_script_test");

    assertEquals("intellij", result.getStdout().trim());
  }

  @Test
  public void testScalaProject() throws InterruptedException, IOException {
    runBuckProjectAndVerify("scala_project");
  }

  @Test
  public void testIgnoredPathAddedToExcludedFolders() throws InterruptedException, IOException {
    runBuckProjectAndVerify("ignored_excluded");
  }

  private ProcessResult runBuckProjectAndVerify(String folderWithTestData, String... commandArgs)
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, folderWithTestData, temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(Lists.asList("project", commandArgs).toArray(new String[0]));
    result.assertSuccess("buck project should exit cleanly");

    workspace.verify();

    return result;
  }
}
