/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.alias;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class AliasIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  // Only works when --show-output is used
  private String buildAndReturnResultContents(ProjectWorkspace workspace, String... args)
      throws IOException {
    Path path = workspace.buildAndReturnOutput(args);
    return workspace.getFileContents(path).trim();
  }

  @Test
  public void aliasesAreBuildableDirectly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String firstBuildResult =
        buildAndReturnResultContents(
            workspace, "//aliases:foo-direct", "--target-platforms", "//config:platform3");
    assertEquals("FOO3", firstBuildResult);

    String secondBuildResult =
        buildAndReturnResultContents(
            workspace, "//aliases:foo-direct", "--target-platforms", "//config:platform4");
    assertEquals("FOO4", secondBuildResult);
  }

  @Test
  public void buildingAliasGivesSameOutputAsBuildingTargetDirectly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String aliasBuildResult =
        buildAndReturnResultContents(
            workspace, "//aliases:foo-direct", "--target-platforms", "//config:platform1");
    String directBuildResult = buildAndReturnResultContents(workspace, "//lib:foo");

    assertEquals(directBuildResult, aliasBuildResult);
  }

  @Test
  public void buildingAliasWithDefaultTargetPlatformConfiguresActualForThatPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String aliasBuildResult =
        buildAndReturnResultContents(
            workspace, "//aliases:foo-direct-with-default-target-platform");

    String directBuildResult =
        buildAndReturnResultContents(
            workspace, "//lib:foo", "--target-platforms", "//config:platform2");

    assertEquals(directBuildResult, aliasBuildResult);
  }

  @Test
  public void buildingConfiguredAliasGivesSameOutputAsBuildingTargetDirectly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String aliasBuildResult =
        buildAndReturnResultContents(workspace, "//aliases:foo-with-platform2");

    String directBuildResult =
        buildAndReturnResultContents(
            workspace, "//lib:foo", "--target-platforms", "//config:platform2");

    assertEquals(directBuildResult, aliasBuildResult);
  }

  @Test
  public void buildingTargetThatDependsOnAliasIncludesActualTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    Path aliasOutputDirectoryPath =
        workspace.buildAndReturnOutput("//groups:foo-and-bar-with-platform2-aliases");
    String aliasBuildResultFoo =
        workspace.getFileContents(aliasOutputDirectoryPath + File.separator + "foo.txt");
    String aliasBuildResultBar =
        workspace.getFileContents(aliasOutputDirectoryPath + File.separator + "bar.txt");

    Path directOutputDirectoryPath =
        workspace.buildAndReturnOutput("//groups:foo-and-bar-with-platform2-directly");
    String directBuildResultFoo =
        workspace.getFileContents(directOutputDirectoryPath + File.separator + "foo.txt");
    String directBuildResultBar =
        workspace.getFileContents(directOutputDirectoryPath + File.separator + "bar.txt");

    assertEquals(directBuildResultFoo, aliasBuildResultFoo);
    assertEquals(directBuildResultBar, aliasBuildResultBar);
  }

  @Test
  public void buildingTargetWhichDependsOnSameUnderlyingTargetTwice() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("build", "--show-output", "//groups:bar-included-twice-via-alias");
    // TODO(srice): Should this be possible / allowed?
    result.assertFailure();
    assertTrue(result.getStderr().contains("The file 'bar.txt' appears twice in the hierarchy"));
  }

  @Test
  public void canUseSelectToSwitchActualTargetConditionally() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String defaultResult =
        buildAndReturnResultContents(
            workspace, "//aliases:different-targets-based-on-configuration");
    // Points to bar and builds it for platform4 (the DTP of the alias)
    assertEquals("BAR-DEFAULT", defaultResult);

    String platform1Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:different-targets-based-on-configuration",
            "--target-platforms",
            "//config:platform1");
    // Points to bar and builds it for platform1
    assertEquals("BAR1", platform1Result);

    String platform3Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:different-targets-based-on-configuration",
            "--target-platforms",
            "//config:platform3");
    // Points to foo and builds it for platform3
    assertEquals("FOO3", platform3Result);
  }

  @Test
  public void canUseSelectToSwitchConfigurationsConditionally() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    String platform1Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:foo-with-selectable-platform",
            "--target-platforms",
            "//config:platform1");
    // platform1 is set up to configure foo for platform3
    assertEquals("FOO3", platform1Result);

    String platform2Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:foo-with-selectable-platform",
            "--target-platforms",
            "//config:platform2");
    // platform2 is set up to configure foo for platform4
    assertEquals("FOO4", platform2Result);

    String platform3Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:foo-with-selectable-platform",
            "--target-platforms",
            "//config:platform3");
    // platform3 is set up to configure foo for platform1
    assertEquals("FOO1", platform3Result);

    String platform4Result =
        buildAndReturnResultContents(
            workspace,
            "//aliases:foo-with-selectable-platform",
            "--target-platforms",
            "//config:platform4");
    // platform4 is set up to configure foo for platform2
    assertEquals("FOO2", platform4Result);
  }

  @Test
  public void canUseAliasesToSwitchPlatformsWithinASingleTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_aliases", tmp);
    workspace.setUp();

    Path platform4OutputDirectoryPath =
        workspace.buildAndReturnOutput("//groups:group-of-aliases-using-select");
    String platform4BuildResultFoo =
        workspace.getFileContents(platform4OutputDirectoryPath + File.separator + "foo.txt");
    String platform4BuildResultBar =
        workspace.getFileContents(platform4OutputDirectoryPath + File.separator + "bar.txt");

    // Foo - built via foo-with-selectable-platform with platform2
    assertEquals("FOO2", platform4BuildResultFoo.trim());
    // Bar - built via different-targets-based-on-configuration with platform4
    assertEquals("BAR-DEFAULT", platform4BuildResultBar.trim());

    Path platform1OutputDirectoryPath =
        workspace.buildAndReturnOutput(
            "//groups:group-of-aliases-using-select", "--target-platforms", "//config:platform1");
    String platform1BuildResultFoo =
        workspace.getFileContents(platform1OutputDirectoryPath + File.separator + "foo.txt");
    String platform1BuildResultBar =
        workspace.getFileContents(platform1OutputDirectoryPath + File.separator + "bar.txt");

    // Foo - built via foo-with-selectable-platform with platform3
    assertEquals("FOO3", platform1BuildResultFoo.trim());
    // Bar - built via different-targets-based-on-configuration with platform1
    assertEquals("BAR1", platform1BuildResultBar.trim());
  }
}
