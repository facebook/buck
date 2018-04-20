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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.w3c.dom.Node;

public class ProjectIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    // These tests consistently fail on Windows due to path separator issues.
    Assume.assumeFalse(Platform.detect() == Platform.WINDOWS);
  }

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
            this, "project_with_unused_libraries", temporaryFolder, true);
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
  public void testGeneratingAndroidManifestWithMinSdkWithDifferentVersionsFromManifest()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("min_sdk_version_different_from_manifests");
  }

  @Test
  public void testGeneratingAndroidManifestWithMinSdkFromBinaryManifest()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("min_sdk_version_from_binary_manifest");
  }

  @Test
  public void testGeneratingAndroidManifestWithMinSdkFromBuckConfig()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("min_sdk_version_from_buck_config");
  }

  @Test
  public void testGeneratingAndroidManifestWithNoMinSdkConfig()
      throws InterruptedException, IOException {
    runBuckProjectAndVerify("min_sdk_version_with_no_config");
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

  @Test
  public void testBuckModuleRegenerateSubproject() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
                this, "incrementalProject", temporaryFolder.newFolder())
            .setUp();
    final String extraModuleFilePath = "modules/extra/modules_extra.iml";
    final File extraModuleFile = workspace.getPath(extraModuleFilePath).toFile();
    workspace
        .runBuckCommand("project", "--intellij-aggregation-mode=none", "//modules/tip:tip")
        .assertSuccess();
    assertFalse(extraModuleFile.exists());
    final String modulesBefore = workspace.getFileContents(".idea/modules.xml");
    final String fileXPath =
        String.format(
            "/project/component/modules/module[contains(@filepath,'%s')]", extraModuleFilePath);
    assertThat(XmlDomParser.parse(modulesBefore), Matchers.not(Matchers.hasXPath(fileXPath)));

    // Run regenerate on the new modules
    workspace
        .runBuckCommand(
            "project", "--intellij-aggregation-mode=none", "--update", "//modules/extra:extra")
        .assertSuccess();
    assertTrue(extraModuleFile.exists());
    final String modulesAfter = workspace.getFileContents(".idea/modules.xml");
    assertThat(XmlDomParser.parse(modulesAfter), Matchers.hasXPath(fileXPath));
    workspace.verify();
  }

  @Test
  public void testBuckModuleRegenerateSubprojectNoOp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
                this, "incrementalProject", temporaryFolder.newFolder())
            .setUp();
    workspace
        .runBuckCommand(
            "project",
            "--intellij-aggregation-mode=none",
            "//modules/tip:tip",
            "//modules/extra:extra")
        .assertSuccess();
    workspace.verify();
    // Run regenerate, should be a no-op relative to previous
    workspace
        .runBuckCommand(
            "project", "--intellij-aggregation-mode=none", "--update", "//modules/extra:extra")
        .assertSuccess();
    workspace.verify();
  }

  @Test
  public void testCrossCellIntelliJProject() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "inter-cell/primary", temporaryFolder.newFolder());
    primary.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "inter-cell/secondary", temporaryFolder.newFolder());
    secondary.setUp();

    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    // First try with cross-cell enabled
    String target = "//apps/sample:app_with_cross_cell_android_lib";
    ProcessResult result =
        primary.runBuckCommand(
            "project",
            "--config",
            "project.embedded_cell_buck_out_enabled=true",
            "--ide",
            "intellij",
            target);
    result.assertSuccess();

    String libImlPath = ".idea/libraries/secondary__java_com_crosscell_crosscell.xml";
    Node doc = XmlDomParser.parse(primary.getFileContents(libImlPath));
    String urlXpath = "/component/library/CLASSES/root/@url";
    // Assert that the library URL is inside the project root
    assertThat(
        doc,
        Matchers.hasXPath(
            urlXpath, Matchers.startsWith("jar://$PROJECT_DIR$/buck-out/cells/secondary/gen/")));

    result =
        primary.runBuckCommand(
            "project",
            "--config",
            "project.embedded_cell_buck_out_enabled=false",
            "--ide",
            "intellij",
            target);
    result.assertSuccess();

    Node doc2 = XmlDomParser.parse(primary.getFileContents(libImlPath));
    // Assert that the library URL is outside the project root
    assertThat(doc2, Matchers.hasXPath(urlXpath, Matchers.startsWith("jar://$PROJECT_DIR$/..")));
  }

  private ProcessResult runBuckProjectAndVerify(String folderWithTestData, String... commandArgs)
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, folderWithTestData, temporaryFolder, true);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(Lists.asList("project", commandArgs).toArray(new String[0]));
    result.assertSuccess("buck project should exit cleanly");

    workspace.verify();

    return result;
  }
}
