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

package com.facebook.buck.core.rules.configsetting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class ConfigSettingIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testSelectWorksWithConfigurationValues() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("-c", "cat.file=a", ":cat");
    assertEquals("a", Files.readAllLines(output).get(0));

    output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":cat");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testUnresolvedConfigurationFailsTheBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild(":cat");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "None of the conditions in attribute \"srcs\" match the configuration. Checked conditions:\n"
                + " //:a\n"
                + " //:b"));
  }

  @Test
  public void testDefaultIsUsedWhenNothingMatches() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput(":cat_with_default");
    assertEquals("c", Files.readAllLines(output).get(0));
  }

  @Test
  public void testSelectLessSpecializedConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("-c", "cat.file=b", ":cat_with_refined_config");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testSelectMoreSpecializedConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "-c", "cat.file=b", "-c", "cat.file2=c", ":cat_with_refined_config");
    assertEquals("c", Files.readAllLines(output).get(0));
  }

  @Test
  public void testNoneSetsValueToNull() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput(":echo");
    assertEquals("cmd", Files.readAllLines(output).get(0).trim());

    output = workspace.buildAndReturnOutput("-c", "cat.file=a", ":echo");
    assertEquals("select", Files.readAllLines(output).get(0).trim());
  }

  @Test
  public void testConflictingConditionsWithNoneCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckBuild("-c", "cat.file=a", "-c", "another.option=c", ":echo_with_one_none");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "Multiple matches found when resolving configurable attribute \"cmd\" in //:echo_with_one_none"));
  }

  @Test
  public void testConfigSettingCanResolveConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("--target-platforms", "//:osx_x86-64", ":cat");
    assertEquals("a", Files.readAllLines(output).get(0));

    output = workspace.buildAndReturnOutput("--target-platforms", "//:linux_aarch64", ":cat");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testConfigSettingCanResolveConstraintsAndValues() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "-c",
            "cat.file=a",
            "--target-platforms",
            "//:osx_x86-64",
            ":cat_with_constraints_and_values");
    assertEquals("a", Files.readAllLines(output).get(0));

    output =
        workspace.buildAndReturnOutput(
            "-c",
            "cat.file=b",
            "--target-platforms",
            "//:linux_aarch64",
            ":cat_with_constraints_and_values");
    assertEquals("b", Files.readAllLines(output).get(0));
  }

  @Test
  public void testNonPlatformRuleCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("--target-platforms", "//:osx", ":cat");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "//:osx is used as a target platform, but not declared using `platform` rule"));
  }

  @Test
  public void testNonMatchingPlatformCauseError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_constraints", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult =
        workspace.runBuckBuild("--target-platforms", "//:linux_arm", ":cat");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "None of the conditions in attribute \"srcs\" match the configuration. Checked conditions:\n"
                + " //:osx_config\n"
                + " //:linux_aarch64_config"));
  }
}
