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

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class ExternalTestRunnerIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this,
            "external_test_runner",
            tmp);
    workspace.setUp();
  }

  @Test
  public void runPass() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c", "test.external_runner=" + workspace.getPath("test_runner.py"),
            "//:pass");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        Matchers.equalTo("TESTS PASSED!\n"));
  }

  @Test
  public void runFail() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c", "test.external_runner=" + workspace.getPath("test_runner.py"),
            "//:fail");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        Matchers.endsWith("TESTS FAILED!\n"));
  }

  @Test
  public void runJavaTest() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "-c", "test.external_runner=" + workspace.getPath("test_runner.py"),
            "//:simple");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        Matchers.matchesPattern(
            Joiner.on(System.lineSeparator()).join(
                "<\\?xml version=\"1.1\" encoding=\"UTF-8\" standalone=\"no\"\\?>",
                "<testcase name=\"SimpleTest\">",
                "  <test name=\"passingTest\" success=\"true\" time=\"\\d*\" type=\"SUCCESS\">",
                "    <stdout>passed!",
                "</stdout>",
                "  </test>",
                "</testcase>") + System.lineSeparator()));
  }

}
