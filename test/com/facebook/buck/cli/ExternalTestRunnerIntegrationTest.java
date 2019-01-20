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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.facebook.buck.cxx.CxxToolchainUtilsForTests;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.environment.PlatformType;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalTestRunnerIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private boolean isWindowsOs;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "external_test_runner", tmp);
    workspace.setUp();
    isWindowsOs = Platform.detect().getType() == PlatformType.WINDOWS;
  }

  @Test
  public void runPass() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "-c", "test.external_runner=" + workspace.getPath("test_runner.py"), "//:pass");
    result.assertSuccess();
    assertThat(result.getStdout(), is(equalTo("TESTS PASSED!\n")));
  }

  @Test
  public void runCoverage() throws IOException {
    String externalTestRunner =
        isWindowsOs ? "test_runner_coverage.bat" : "test_runner_coverage.py";
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath(externalTestRunner),
            "//dir:python-coverage");
    result.assertSuccess();
    String simplePath = MorePaths.pathWithPlatformSeparators("dir/simple.py").replace("\\", "\\\\");
    String alsoSimplePath =
        MorePaths.pathWithPlatformSeparators("dir/also_simple.py").replace("\\", "\\\\");
    assertThat(
        result.getStdout().trim(),
        is(
            endsWith(
                String.format(
                    "[[0.0, ['%1$s']], " + "[0.75, ['%2$s', '%1$s']], " + "[1.0, ['%2$s']]]",
                    simplePath, alsoSimplePath))));
  }

  @Test
  public void runPythonCxxAdditionalCoverage() throws IOException {
    CxxToolchainUtilsForTests.configureCxxToolchains(workspace);
    String externalTestRunner =
        isWindowsOs ? "test_runner_additional_coverage.bat" : "test_runner_additional_coverage.py";
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath(externalTestRunner),
            "//dir:python-cxx-additional-coverage");

    result.assertSuccess();
    if (isWindowsOs) {
      assertTrue(result.getStdout().trim().endsWith("\\buck-out\\gen\\dir\\cpp_binary.exe"));
    } else {
      assertTrue(result.getStdout().trim().endsWith("/buck-out/gen/dir/cpp_binary"));
    }
  }

  @Test
  public void runAdditionalCoverage() throws IOException {
    CxxToolchainUtilsForTests.configureCxxToolchains(workspace);
    String externalTestRunner =
        isWindowsOs ? "test_runner_additional_coverage.bat" : "test_runner_additional_coverage.py";
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath(externalTestRunner),
            "//dir:cpp_test");

    result.assertSuccess();
    if (isWindowsOs) {
      assertTrue(result.getStdout().trim().endsWith("\\buck-out\\gen\\dir\\cpp_binary.exe"));
    } else {
      assertTrue(result.getStdout().trim().endsWith("/buck-out/gen/dir/cpp_binary"));
    }
  }

  @Test
  public void runFail() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "-c", "test.external_runner=" + workspace.getPath("test_runner.py"), "//:fail");
    result.assertSuccess();
    assertThat(result.getStderr(), endsWith("TESTS FAILED!\n"));
  }

  @Test
  public void extraArgs() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo.py"),
            "//:pass",
            "--",
            "bobloblawlobslawbomb");
    result.assertSuccess();
    assertThat(result.getStdout().trim(), is(equalTo("bobloblawlobslawbomb")));
  }

  @Test
  public void runJavaTest() throws IOException {
    String externalTestRunner = isWindowsOs ? "test_runner.bat" : "test_runner.py";
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath(externalTestRunner),
            "//:simple");
    result.assertSuccess();
    String expected =
        Joiner.on(System.lineSeparator())
                .join(
                    "(?s).*<\\?xml version=\"1.1\" encoding=\"UTF-8\" standalone=\"no\"\\?>",
                    "<testcase name=\"SimpleTest\" runner_capabilities=\"simple_test_selector\">",
                    "  <test name=\"passingTest\" success=\"true\" suite=\"SimpleTest\" "
                        + "time=\"\\d*\" type=\"SUCCESS\">",
                    "    <stdout>passed!",
                    "</stdout>",
                    "  </test>",
                    "</testcase>",
                    "<\\?xml version=\"1.1\" encoding=\"UTF-8\" standalone=\"no\"\\?>",
                    "<testcase name=\"SimpleTest2\" runner_capabilities=\"simple_test_selector\">",
                    "  <test name=\"passingTest\" success=\"true\" suite=\"SimpleTest2\" "
                        + "time=\"\\d*\" type=\"SUCCESS\">",
                    "    <stdout>passed!",
                    "</stdout>",
                    "  </test>",
                    "</testcase>")
            + System.lineSeparator();
    assertThat(result.getStdout(), Matchers.matchesPattern(expected));
  }

  @Test
  public void numberOfJobsIsPassedToExternalRunner() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo_jobs.py"),
            "//:pass",
            "-j",
            "13");
    result.assertSuccess();
    assertThat(result.getStdout().trim(), is(equalTo("13")));
  }

  @Test
  public void numberOfJobsInExtraArgsIsPassedToExternalRunner() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo_all_args.py"),
            "//:pass",
            "--",
            "--jobs",
            "13");
    result.assertSuccess();

    List<String> args = Arrays.asList(result.getStdout().trim().split(" "));
    int jobsIndex = args.indexOf("--jobs");

    // exists
    assertThat(jobsIndex, greaterThanOrEqualTo(0));
    // appears only once
    assertThat(jobsIndex, equalTo(args.lastIndexOf("--jobs")));
    // not the last token
    assertThat(jobsIndex, lessThan(args.size() - 1));
    // expected value
    assertThat(args.get(jobsIndex + 1), equalTo("13"));
  }

  @Test
  public void numberOfJobsInExtraArgsWithShortNotationIsPassedToExternalRunner()
      throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo_all_args.py"),
            "//:pass",
            "--",
            "-j",
            "10");
    result.assertSuccess();

    List<String> args = Arrays.asList(result.getStdout().trim().split(" "));
    int jobsIndex = args.indexOf("-j");

    // exists
    assertThat(jobsIndex, greaterThanOrEqualTo(0));
    // appears only once
    assertThat(jobsIndex, equalTo(args.lastIndexOf("-j")));
    // not the last token
    assertThat(jobsIndex, lessThan(args.size() - 1));
    // expected value
    assertThat(args.get(jobsIndex + 1), equalTo("10"));
  }

  @Test
  public void numberOfJobsWithUtilizationRatioAppliedIsPassedToExternalRunner() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo_jobs.py"),
            "-c",
            "test.thread_utilization_ratio=0.5",
            "//:pass",
            "-j",
            "13");
    result.assertSuccess();
    assertThat(result.getStdout().trim(), is(equalTo("7")));
  }

  @Test
  public void numberOfJobsWithTestThreadsIsPassedToExternalRunner() throws IOException {
    // sh_test doesn't support Windows
    assumeFalse(isWindowsOs);
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("test_runner_echo_jobs.py"),
            "-c",
            "test.threads=2",
            "//:pass");
    result.assertSuccess();
    assertThat(result.getStdout().trim(), is(equalTo("2")));
  }
}
