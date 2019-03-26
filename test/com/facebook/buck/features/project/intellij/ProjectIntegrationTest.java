/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

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
import com.google.common.collect.Streams;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.w3c.dom.Node;

public class ProjectIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public TemporaryPaths temporaryFolder2 = new TemporaryPaths();

  @Before
  public void setUp() {
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

  @Test
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
  public void testJavaTestRule() throws InterruptedException, IOException {
    runBuckProjectAndVerify("java_test");
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
  public void testCxxTest() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_cxx_test");
  }

  @Test
  public void testAggregatingCxxTest() throws InterruptedException, IOException {
    runBuckProjectAndVerify("aggregation_with_cxx_test");
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
  public void testProjectWithPrebuiltJarExportedDeps() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_prebuilt_exported_deps", "//a:a");
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
  public void testProjectWithBinaryInputs() throws InterruptedException, IOException {
    runBuckProjectAndVerify("project_with_binary_inputs");
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
  public void testImlsInIdea() throws InterruptedException, IOException {
    runBuckProjectAndVerify("imls_in_idea");
  }

  @Test
  public void testPythonLibrary() throws InterruptedException, IOException {
    runBuckProjectAndVerify("python_library");
  }

  @Test
  public void testOutputDir() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "min_sdk_version_from_binary_manifest", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("project").assertSuccess("buck project should exit cleanly");
    Path outPath = temporaryFolder2.getRoot();
    workspace
        .runBuckCommand("project", "--output-dir", outPath.toString())
        .assertSuccess("buck project should exit cleanly");

    // Check every file in output-dir matches one in project
    for (File outFile : Files.fileTreeTraverser().children(outPath.toFile())) {
      Path relativePath = outPath.relativize(Paths.get(outFile.getPath()));
      File projFile = temporaryFolder.getRoot().resolve(relativePath).toFile();
      assertTrue(projFile.exists());
      if (projFile.isFile()) {
        assertTrue(Files.asByteSource(projFile).contentEquals(Files.asByteSource(outFile)));
      }
    }
  }

  Iterable<File> recursePath(Path path) {
    return Files.fileTreeTraverser().breadthFirstTraversal(path.toFile());
  }

  @Test
  public void testOutputDirNoProjectWrite() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "min_sdk_version_from_binary_manifest", temporaryFolder);
    workspace.setUp();

    Path projPath = temporaryFolder.getRoot();
    Path outPath = temporaryFolder2.getRoot();

    long lastModifiedProject =
        Streams.stream(recursePath(projPath)).map(File::lastModified).max(Long::compare).get();
    ProcessResult result = workspace.runBuckCommand("project", "--output-dir", outPath.toString());
    result.assertSuccess("buck project should exit cleanly");

    for (File file : recursePath(projPath)) {
      if (!(file.isDirectory()
          || file.getPath().contains("log")
          || file.getName().equals(".progressestimations.json")
          || file.getName().equals(".currentversion"))) {
        assertTrue(file.lastModified() <= lastModifiedProject);
      }
    }
  }

  @Test
  public void testDifferentOutputDirSameProject() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "min_sdk_version_from_binary_manifest", temporaryFolder);
    workspace.setUp();

    Path out1Path = temporaryFolder2.newFolder("project1");
    // Make sure buck project creates a dir if it doesn't exist
    Path out2Path = temporaryFolder2.getRoot().resolve("project2/subdir");
    workspace
        .runBuckCommand("project", "--output-dir", out1Path.toString())
        .assertSuccess("buck project should exit cleanly");
    workspace
        .runBuckCommand("project", "--output-dir", out2Path.toString())
        .assertSuccess("buck project should exit cleanly");

    List<File> out1Files = Lists.newArrayList(recursePath(out1Path));
    List<File> out2Files = Lists.newArrayList(recursePath(out2Path));
    assertEquals(out1Files.size(), out2Files.size());
    for (File file1 : out1Files) {
      Path relativePath = out1Path.relativize(Paths.get(file1.getPath()));
      File file2 = out2Path.resolve(relativePath).toFile();
      assertTrue(file2.exists());
      if (file1.isFile()) {
        assertTrue(Files.asByteSource(file1).contentEquals(Files.asByteSource(file2)));
      }
    }
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
  public void testBuckModuleRegenerateWithExportedLibs() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
                this, "incrementalProject", temporaryFolder.newFolder())
            .setUp();
    final String libraryFilePath = ".idea/libraries/__modules_lib_guava.xml";
    final File libraryFile = workspace.getPath(libraryFilePath).toFile();
    workspace
        .runBuckCommand("project", "--intellij-aggregation-mode=none", "//modules/tip:tip")
        .assertSuccess();
    assertFalse(libraryFile.exists());
    // Run regenerate and we should see the library file get created
    workspace
        .runBuckCommand(
            "project",
            "--intellij-aggregation-mode=none",
            "--update",
            "//modules/tip:tipwithexports")
        .assertSuccess();
    assertTrue(libraryFile.exists());
  }

  @Test
  public void testCrossCellIntelliJProject() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "inter-cell/primary", temporaryFolder.newFolder());
    primary.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
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

  @Test
  public void testGeneratingModulesInMultiCells() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "modules_in_multi_cells/primary", temporaryFolder.newFolder("primary"));
    primary.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "modules_in_multi_cells/secondary", temporaryFolder.newFolder("secondary"));
    secondary.setUp();

    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    String target = "//java/com/sample/app:app";
    ProcessResult result =
        primary.runBuckCommand(
            "project",
            "--config",
            "intellij.multi_cell_module_support=true",
            "--config",
            "intellij.keep_module_files_in_module_dirs=true",
            "--intellij-aggregation-mode=None",
            "--ide",
            "intellij",
            target);
    result.assertSuccess();
    primary.verify();
    secondary.verify();
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
