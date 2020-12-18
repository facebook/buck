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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Verifies that {@code buck build --keep-going} works as intended. */
@RunWith(Parameterized.class)
public class BuildKeepGoingIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TemporaryPaths tmpFolderForBuildReport = new TemporaryPaths();

  @Parameters
  public static Iterable<Boolean> inputs() {
    return Arrays.asList(true, false);
  }

  @Parameter public boolean useConfig;

  private ProjectWorkspace workspace;
  private ImmutableList.Builder<String> runBuckBuildArgumentsBuilder;

  @Before
  public void setUp() throws IOException {
    String scenario = "keep_going";
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp).setUp();

    runBuckBuildArgumentsBuilder = new ImmutableList.Builder<>();

    if (useConfig) {
      workspace.addBuckConfigLocalOption("build", "keep_going", "true");
    } else {
      runBuckBuildArgumentsBuilder.add("--keep-going");
    }
  }

  @Test
  public void testKeepGoingWithMultipleSuccessfulTargets() throws IOException {
    ProcessResult result = buildTwoGoodRulesAndAssertSuccess();
    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem().getBuckPaths(),
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
    runBuckBuildArgumentsBuilder.add("//:rule_with_output", "//:failing_rule");
    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem().getBuckPaths(),
                BuildTargetFactory.newInstance("//:rule_with_output"),
                "%s")
            .resolve("rule_with_output.txt")
            .toString();
    ProcessResult result =
        workspace
            .runBuckBuild(buildArgsAndReturnArray(runBuckBuildArgumentsBuilder))
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
    workspace.enableDirCache();

    // genrule uses legacy format
    String genruleOutputPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem().getBuckPaths(),
                BuildTargetFactory.newInstance("//:rule_with_output"),
                "%s")
            .resolve("rule_with_output.txt")
            .toString();
    ProcessResult result1 = buildTwoGoodRulesAndAssertSuccess();
    String expectedReport1 =
        linesToText(
            "OK   //:rule_with_output BUILT_LOCALLY " + genruleOutputPath,
            "OK   //:rule_without_output BUILT_LOCALLY",
            "");
    assertThat(result1.getStderr(), containsString(expectedReport1));

    ProcessResult result2 = buildTwoGoodRulesAndAssertSuccess();
    String expectedReport2 =
        linesToText(
            "OK   //:rule_with_output MATCHING_RULE_KEY " + genruleOutputPath,
            "OK   //:rule_without_output MATCHING_RULE_KEY",
            "");
    assertThat(result2.getStderr(), containsString(expectedReport2));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    ProcessResult result3 = buildTwoGoodRulesAndAssertSuccess();
    String expectedReport3 =
        linesToText(
            "OK   //:rule_with_output FETCHED_FROM_CACHE " + genruleOutputPath,
            "OK   //:rule_without_output BUILT_LOCALLY",
            "");
    assertThat(result3.getStderr(), containsString(expectedReport3));
  }

  @Test
  public void testKeepGoingWithBuildReport() throws IOException {
    AbsPath buildReport = tmpFolderForBuildReport.getRoot().resolve("build-report.txt");
    runBuckBuildArgumentsBuilder.add(
        "--build-report", buildReport.toString(), "//:rule_with_output", "//:failing_rule");
    workspace.runBuckBuild(buildArgsAndReturnArray(runBuckBuildArgumentsBuilder)).assertFailure();

    TestUtils.assertBuildReport(workspace, tmp, buildReport, "expected_build_report.json");
  }

  private ProcessResult buildTwoGoodRulesAndAssertSuccess() {
    runBuckBuildArgumentsBuilder.add("//:rule_with_output", "//:rule_without_output");
    return workspace
        .runBuckBuild(buildArgsAndReturnArray(runBuckBuildArgumentsBuilder))
        .assertSuccess();
  }

  private String[] buildArgsAndReturnArray(ImmutableList.Builder<String> builder) {
    ImmutableList<String> list = builder.build();
    return list.toArray(new String[0]);
  }
}
