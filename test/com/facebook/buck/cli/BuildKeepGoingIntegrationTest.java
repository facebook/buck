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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that {@code buck build --keep-going} works as intended.
 */
public class BuildKeepGoingIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmpFolderForBuildReport = new DebuggableTemporaryFolder();

  @Test
  public void testKeepGoingWithMultipleSuccessfulTargets() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "keep_going", tmp).setUp();

    ProcessResult result = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport =
        "OK   //:rule_with_output BUILT_LOCALLY buck-out/gen/rule_with_output.txt\n" +
        "OK   //:rule_without_output BUILT_LOCALLY\n";
    assertThat(result.getStderr(), containsString(expectedReport));
  }

  @Test
  public void testKeepGoingWithOneFailingTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "keep_going", tmp).setUp();

    ProcessResult result = workspace.runBuckBuild(
        "--keep-going",
        "//:rule_with_output",
        "//:failing_rule")
        .assertFailure();
    String pathToOutputFile = "buck-out/gen/rule_with_output.txt";
    String expectedReport =
        "OK   //:rule_with_output BUILT_LOCALLY " + pathToOutputFile + "\n" +
        "FAIL //:failing_rule\n";
    assertThat(result.getStderr(), containsString(expectedReport));
    Path outputFile = workspace.getPath(pathToOutputFile);
    assertTrue(Files.exists(outputFile));
  }

  @Test
  public void testVariousSuccessTypesInReport() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "keep_going", tmp).setUp();

    ProcessResult result1 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport1 =
        "OK   //:rule_with_output BUILT_LOCALLY buck-out/gen/rule_with_output.txt\n" +
        "OK   //:rule_without_output BUILT_LOCALLY\n";
    assertThat(result1.getStderr(), containsString(expectedReport1));

    ProcessResult result2 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport2 =
        "OK   //:rule_with_output MATCHING_RULE_KEY buck-out/gen/rule_with_output.txt\n" +
        "OK   //:rule_without_output MATCHING_RULE_KEY\n";
    assertThat(result2.getStderr(), containsString(expectedReport2));

    workspace.runBuckCommand("clean").assertSuccess();

    ProcessResult result3 = buildTwoGoodRulesAndAssertSuccess(workspace);
    String expectedReport3 =
        "OK   //:rule_with_output FETCHED_FROM_CACHE buck-out/gen/rule_with_output.txt\n" +
        "OK   //:rule_without_output FETCHED_FROM_CACHE\n";
    assertThat(result3.getStderr(), containsString(expectedReport3));
  }

  @Test
  public void testKeepGoingWithBuildReport() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "keep_going", tmp).setUp();

    File buildReport = new File(tmpFolderForBuildReport.getRoot(), "build-report.txt");
    workspace.runBuckBuild(
        "--build-report",
        buildReport.getAbsolutePath(),
        "--keep-going",
        "//:rule_with_output",
        "//:failing_rule")
        .assertFailure();

    assertTrue(buildReport.exists());
    String buildReportContents = com.google.common.io.Files.toString(buildReport, Charsets.UTF_8);
    String expectedReport = Joiner.on('\n').join(
        "{",
        "  \"success\" : false,",
        "  \"results\" : {",
        "    \"//:rule_with_output\" : {",
        "      \"success\" : true,",
        "      \"type\" : \"BUILT_LOCALLY\",",
        "      \"output\" : \"buck-out/gen/rule_with_output.txt\"",
        "    },",
        "    \"//:failing_rule\" : {",
        "      \"success\" : false",
        "    }",
        "  }",
        "}");
    assertEquals(expectedReport, buildReportContents);
  }

  private static ProcessResult buildTwoGoodRulesAndAssertSuccess(ProjectWorkspace workspace)
      throws IOException {
    return workspace.runBuckBuild(
        "--keep-going",
        "//:rule_with_output",
        "//:rule_without_output")
        .assertSuccess();
  }
}
