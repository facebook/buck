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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

/** Verifies that {@code buck build --keep-going} works as intended. */
public class BuildKeepGoingIntegrationTest {

  private static final String GENRULE_OUTPUT = "buck-out/gen/rule_with_output/rule_with_output.txt";
  private static final String GENRULE_OUTPUT_PATH =
      MorePaths.pathWithPlatformSeparators("buck-out/gen/rule_with_output/rule_with_output.txt");
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths tmpFolderForBuildReport = new TemporaryPaths();

  @Test
  public void testKeepGoingWithMultipleSuccessfulTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();

    ProcessResult result = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport =
        "OK   //:rule_with_output BUILT_LOCALLY "
            + GENRULE_OUTPUT_PATH
            + "\n"
            + "OK   //:rule_without_output BUILT_LOCALLY\n";
    assertThat(result.getStderr(), containsString(expectedReport));
  }

  @Test
  public void testKeepGoingWithOneFailingTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();

    ProcessResult result =
        workspace
            .runBuckBuild("--keep-going", "//:rule_with_output", "//:failing_rule")
            .assertFailure();
    String expectedReport =
        "OK   //:rule_with_output BUILT_LOCALLY "
            + GENRULE_OUTPUT_PATH
            + "\n"
            + "FAIL //:failing_rule\n";
    assertThat(result.getStderr(), containsString(expectedReport));
    Path outputFile = workspace.getPath(GENRULE_OUTPUT);
    assertTrue(Files.exists(outputFile));
  }

  @Test
  public void testVariousSuccessTypesInReport() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "keep_going", tmp).setUp();
    workspace.enableDirCache();

    ProcessResult result1 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport1 =
        "OK   //:rule_with_output BUILT_LOCALLY "
            + GENRULE_OUTPUT_PATH
            + "\n"
            + "OK   //:rule_without_output BUILT_LOCALLY\n";
    assertThat(result1.getStderr(), containsString(expectedReport1));

    ProcessResult result2 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport2 =
        "OK   //:rule_with_output MATCHING_RULE_KEY "
            + GENRULE_OUTPUT_PATH
            + "\n"
            + "OK   //:rule_without_output MATCHING_RULE_KEY\n";
    assertThat(result2.getStderr(), containsString(expectedReport2));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    ProcessResult result3 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport3 =
        "OK   //:rule_with_output FETCHED_FROM_CACHE "
            + GENRULE_OUTPUT_PATH
            + "\n"
            + "OK   //:rule_without_output BUILT_LOCALLY\n";
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

    assertTrue(Files.exists(buildReport));
    String buildReportContents =
        new String(Files.readAllBytes(buildReport), Charsets.UTF_8).replace("\r\n", "\n");
    assertEquals(workspace.getFileContents("expected_build_report.json"), buildReportContents);
  }

  private static ProcessResult buildTwoGoodRulesAndAssertSuccess(ProjectWorkspace workspace)
      throws IOException {
    return workspace
        .runBuckBuild("--keep-going", "//:rule_with_output", "//:rule_without_output")
        .assertSuccess();
  }
}
