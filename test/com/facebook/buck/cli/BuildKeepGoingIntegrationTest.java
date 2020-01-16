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

package com.facebook.buck.cli;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

/** Verifies that {@code buck build --keep-going} works as intended. */
public class BuildKeepGoingIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths tmpFolderForBuildReport = new TemporaryPaths();

  @Test
  public void testKeepGoingWithMultipleSuccessfulTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();

    ProcessResult result = buildTwoGoodRulesAndAssertSuccess(workspace);
    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//:rule_with_output"),
                "%s")
            .resolve("rule_with_output.txt")
            .toString();
    String expectedReport =
        linesToText(
            "OK   //:rule_with_output BUILT_LOCALLY " + genruleOutputPath,
            "OK   //:rule_without_output BUILT_LOCALLY",
            "");
    assertThat(result.getStderr(), containsString(expectedReport));
  }

  @Test
  public void testKeepGoingWithOneFailingTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();

    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//:rule_with_output"),
                "%s")
            .resolve("rule_with_output.txt")
            .toString();
    ProcessResult result =
        workspace
            .runBuckBuild("--keep-going", "//:rule_with_output", "//:failing_rule")
            .assertFailure();
    String expectedReport =
        linesToText(
            "OK   //:rule_with_output BUILT_LOCALLY " + genruleOutputPath,
            "FAIL //:failing_rule",
            "");
    assertThat(result.getStderr(), containsString(expectedReport));
    Path outputFile = workspace.getPath(genruleOutputPath);
    assertTrue(Files.exists(outputFile));
  }

  @Test
  public void testVariousSuccessTypesInReport() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();
    workspace.enableDirCache();

    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//:rule_with_output"),
                "%s")
            .resolve("rule_with_output.txt")
            .toString();
    ProcessResult result1 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport1 =
        linesToText(
            "OK   //:rule_with_output BUILT_LOCALLY " + genruleOutputPath,
            "OK   //:rule_without_output BUILT_LOCALLY",
            "");
    assertThat(result1.getStderr(), containsString(expectedReport1));

    ProcessResult result2 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport2 =
        linesToText(
            "OK   //:rule_with_output MATCHING_RULE_KEY " + genruleOutputPath,
            "OK   //:rule_without_output MATCHING_RULE_KEY",
            "");
    assertThat(result2.getStderr(), containsString(expectedReport2));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    ProcessResult result3 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport3 =
        linesToText(
            "OK   //:rule_with_output FETCHED_FROM_CACHE " + genruleOutputPath,
            "OK   //:rule_without_output BUILT_LOCALLY",
            "");
    assertThat(result3.getStderr(), containsString(expectedReport3));
  }

  @Test
  public void testKeepGoingWithBuildReport() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();

    Path buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    workspace
        .runBuckBuild(
            "--build-report",
            buildReport.toAbsolutePath().toString(),
            "--keep-going",
            "//:rule_with_output",
            "//:failing_rule")
        .assertFailure();

    TestUtils.assertBuildReport(workspace, tmp, buildReport, "expected_build_report.json");
  }

  private static ProcessResult buildTwoGoodRulesAndAssertSuccess(ProjectWorkspace workspace) {
    return workspace
        .runBuckBuild("--keep-going", "//:rule_with_output", "//:rule_without_output")
        .assertSuccess();
  }
}
