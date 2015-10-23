/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;

import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProjectIntegrationTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testBuckProjectGeneratedSchemeOnlyIncludesDependenciesWithoutTests()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project_generated_scheme_only_includes_dependencies",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "--without-tests",
        "//Apps:workspace");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testBuckProjectGeneratedSchemeIncludesTestsAndDependencies() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project_generated_scheme_includes_tests_and_dependencies",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//Apps:workspace");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testBuckProjectGeneratedSchemeIncludesTestsAndDependenciesInADifferentBuckFile()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project_generated_scheme_includes_tests_and_dependencies_in_a_different_buck_file",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//Apps:workspace");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testBuckProjectGeneratedSchemesDoNotIncludeOtherTests()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project_generated_schemes_do_not_include_other_tests",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void generatingAllWorkspacesWillNotIncludeAllProjectsInEachOfThem() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "generating_all_workspaces_will_not_include_all_projects_in_each_of_them",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void schemeWithActionConfigNames() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "scheme_with_action_config_names",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void schemeWithExtraTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "scheme_with_extra_tests",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void schemeWithExtraTestsWithoutSrcTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "scheme_with_extra_tests_without_src_target",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void generatingCombinedProject() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "generating_combined_project",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "--combined-project",
        "--without-tests",
        "//Apps:workspace");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void generatingCombinedProjectWithTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "generating_combined_project_with_tests",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "--combined-project",
        "//Apps:workspace");
    result.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testGeneratesWorkspaceFromBundle() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_implicit_workspace_generation", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//bin:app");
    result.assertSuccess();
    Files.exists(workspace.resolve("bin/app.xcworkspace/contents.xcworkspacedata"));
    Files.exists(workspace.resolve("bin/bin.xcodeproj/project.pbxproj"));
  }

  @Test
  public void testGeneratesWorkspaceFromLibrary() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_implicit_workspace_generation", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//lib:lib");
    result.assertSuccess();
    Files.exists(workspace.resolve("lib/lib.xcworkspace/contents.xcworkspacedata"));
    Files.exists(workspace.resolve("lib/lib.xcodeproj/project.pbxproj"));
  }

  @Test
  public void testGeneratesWorkspaceFromBinary() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_implicit_workspace_generation", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//bin:bin");
    result.assertSuccess();
    Files.exists(workspace.resolve("bin/bin.xcworkspace/contents.xcworkspacedata"));
    Files.exists(workspace.resolve("bin/bin.xcodeproj/project.pbxproj"));
  }

  @Test
  public void testAttemptingToGenerateWorkspaceFromResourceTargetIsABuildError()
      throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//res:res must be a xcode_workspace_config, apple_binary, apple_bundle, or apple_library");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_implicit_workspace_generation", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand(
        "project",
        "//res:res");
  }

  @Test
  public void testGeneratingProjectWithTargetUsingGenruleSourceBuildsGenrule() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "target_using_genrule_source", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand(
        "project",
        "//lib:lib");

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//lib:gen");
  }

  @Test
  public void testGeneratingProjectWithGenruleResourceBuildsGenrule() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "target_using_genrule_resource", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand(
        "project",
        "//app:TestApp");

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//app:GenResource");
  }

  @Test
  public void testGeneratingWorkspaceForXcodeWithoutSettingIde() throws IOException {
    // .buckconfig has no ide set, so buck should correctly guess that
    // apple_stuff requires Xcode workspace
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "generating_workspace_for_xcode_without_setting_ide",
        temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "project",
        "//App:TestAppBinary");
    result.assertSuccess();

    String workspacePathString = temporaryFolder.getRootPath().toString();
    workspacePathString += "/App/TestAppBinary.xcworkspace";
    Path workspacePath = temporaryFolder.getRootPath().resolve(workspacePathString);

    assertThat(Files.exists(workspacePath), Matchers.equalTo(true));
  }
}
