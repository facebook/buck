/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli.configurations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.MoreStringsForTests;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.hamcrest.junit.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;

public class ConfigurationsIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void targetsInFileFilteredByConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "build", "--target-platforms", "//config:osx_x86_64", "//target_compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//target_compatible_with:cat_on_osx");
    workspace.getBuildLog().assertTargetIsAbsent("//target_compatible_with:cat_on_linux");

    workspace
        .runBuckCommand(
            "build", "--target-platforms", "//config:linux_x86_64", "//target_compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//target_compatible_with:cat_on_linux");
    workspace.getBuildLog().assertTargetIsAbsent("//target_compatible_with:cat_on_osx");
  }

  @Test
  public void targetsInFileFilteredByConfigs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--target-platforms", "//config:osx_x86_64", "//compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//compatible_with:cat_on_osx");
    workspace.getBuildLog().assertTargetIsAbsent("//compatible_with:cat_on_linux");

    workspace
        .runBuckCommand(
            "build", "--target-platforms", "//config:linux_x86_64", "//compatible_with:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//compatible_with:cat_on_linux");
    workspace.getBuildLog().assertTargetIsAbsent("//compatible_with:cat_on_osx");
  }

  @Test
  public void configurationRulesNotIncludedWhenBuildingUsingPattern() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_configuration_rules", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", ":").assertSuccess();
    ImmutableSet<BuildTarget> targets = workspace.getBuildLog().getAllTargets();

    assertEquals(1, targets.size());
    assertEquals("//:echo", Iterables.getOnlyElement(targets).toString());
  }

  @Test
  public void buildDoesNotFailWhenDepDoesNotMatchTargetPlatformAndChecksAreDisables()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--target-platforms",
            "//config:osx_x86-64",
            "-c",
            "parser.enable_target_compatibility_checks=false",
            "//:lib");
    result.assertSuccess();
  }

  @Test
  public void buildFailsWhenDepDoesNotMatchTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("build", "--target-platforms", "//config:osx_x86-64", "//:lib");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Build target //:dep is restricted to constraints "
                + "in \"target_compatible_with\" and \"compatible_with\" "
                + "that do not match the target platform //config:osx_x86-64.\n"
                + "Target constraints:\nbuck//config/constraints:linux"));
  }

  @Test
  public void buildFailsWhenDepCompatiblePlatformDoesNotMatchTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--target-platforms", "//config:osx_x86-64", "//:lib_with_compatible_with");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Build target //:dep_with_compatible_with is restricted to constraints "
                + "in \"target_compatible_with\" and \"compatible_with\" "
                + "that do not match the target platform //config:osx_x86-64.\n"
                + "Target compatible with configurations:\n//config:linux_config"));
  }

  @Test
  public void selectWithoutTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "select_without_target_platform", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:test-library");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "//:test-library: Cannot use select() expression when target platform is not specified"));
  }

  @Test
  public void buildFailsWhenNonConfigurableAttributeUsesSelect() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//invalid:lib");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//invalid:lib: attribute 'compatibleWith' cannot be configured using select"));
  }

  @Test
  public void changingTargetPlatformTriggersRebuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    try (TestContext context = new TestContext()) {
      workspace.runBuckBuild(
          Optional.of(context),
          "--target-platforms",
          "//config:osx_x86-64",
          "//:platform_dependent_genrule");

      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      workspace.runBuckBuild(
          Optional.of(context),
          "--target-platforms",
          "//config:linux_x86-64",
          "//:platform_dependent_genrule");

      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");
    }
  }

  @Test
  public void platformWithCircularDepTriggersFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild(
            "--target-platforms",
            "//config:platform-with-circular-dep",
            "//:platform_dependent_genrule");

    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Buck can't handle circular dependencies.\n"
                + "The following circular dependency has been found:\n"
                + "//config:platform-with-circular-dep -> //config:platform-with-circular-dep"));
  }

  @Test
  public void hostOsConstraintsAreResolvedWithCustomPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Platform platform = Platform.detect();
    String hostPlatform =
        (platform == Platform.LINUX) ? "//config:osx_x86-64" : "//config:linux_x86-64";

    Path output =
        workspace.buildAndReturnOutput(
            "//:platform_dependent_genrule", "-c", "build.host_platform=" + hostPlatform);

    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    String expected = (platform == Platform.LINUX) ? "osx" : "linux";
    assertEquals(expected, workspace.getFileContents(output).trim());
  }

  @Test
  public void cpuConstraintsAreResolvedWithCustomHostPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "//:cpu_dependent_genrule", "--target-platforms", "//config:osx_x86-64");

    workspace.getBuildLog().assertTargetBuiltLocally("//:cpu_dependent_genrule");

    assertEquals("x86_64", workspace.getFileContents(output).trim());
  }

  @Test
  public void buildSucceedsWhenDepMatchesTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--target-platforms", "//config:linux_x86-64", "//:lib")
        .assertSuccess();
  }

  @Test
  public void defaultTargetPlatformIsAppliedWhenNoTargetPlatformSpecified() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:lib-with-default-target-platform").assertSuccess();
  }

  @Test
  public void targetPlatformOverridesDefaultTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "build",
            "--target-platforms",
            "//config:linux_x86-64",
            "//:lib-with-default-target-platform-useless")
        .assertSuccess();
  }

  @Test
  public void defaultTargetPlatformAppliesOnlyToRequestedTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//default_platform_only_leaf:leaf");
    result.assertFailure();

    // TODO(nga): Error is correctly produced by "dep" compatibility check
    // but the error message is incorrect.
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Cannot use select() expression when target platform is not specified"));
  }

  @Test
  public void changesInConfigurationRulesAreDetected() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    try (TestContext context = new TestContext()) {

      Path output =
          workspace.buildAndReturnOutput(
              Optional.of(context),
              "//:platform_dependent_genrule",
              "--target-platforms",
              "//config-change:linux_x86-64");
      String linuxOutput = String.join(" ", Files.readAllLines(output)).trim();
      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      assertEquals("linux", linuxOutput);

      workspace.writeContentsToPath(
          "platform(\n"
              + "    name = \"linux\",\n"
              + "    constraint_values = [\n"
              + "        \"buck//config/constraints:osx\",\n"
              + "    ],\n"
              + "    visibility = [\"PUBLIC\"],\n"
              + ")\n",
          "config-change/platform-dep/BUCK");

      output =
          workspace.buildAndReturnOutput(
              Optional.of(context),
              "//:platform_dependent_genrule",
              "--target-platforms",
              "//config-change:linux_x86-64");
      String osxOutput = String.join(" ", Files.readAllLines(output)).trim();
      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      assertEquals("osx", osxOutput);
    }
  }
}
