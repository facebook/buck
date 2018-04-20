/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.testrunner;

import static com.facebook.buck.testutil.OutputHelper.containsBuckTestOutputLine;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class BuildThenTestIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  /**
   * It is possible to build a test without running it. It is important to make sure that even
   * though a test built successfully, it knows that it needs to run itself if its results are not
   * available.
   */
  @Test
  public void testBuildThenTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "build_then_test", temporaryFolder, true);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:example");
    buildResult.assertSuccess("Successful build should exit with 0.");

    ProcessResult testResult = workspace.runBuckCommand("test", "//:example");
    assertEquals("", testResult.getStdout());
    assertThat(
        "Should contain a line indicating what target it is testing",
        testResult.getStderr(),
        containsString("TESTING //:example"));
    assertThat(
        "Should contain results from the target.",
        testResult.getStderr(),
        containsBuckTestOutputLine("PASS", 1, 0, 0, "com.example.MyTest"));
    assertThat(
        "Should contain a line indicating that tests passed.",
        testResult.getStderr(),
        containsString("TESTS PASSED"));
    testResult.assertSuccess("Passing tests should exit with 0.");
    workspace.verify();
  }

  /** Test should pass even when we run tests on non JUnit test classes */
  @Test
  public void testRunningTestOnClassWithoutTestMethods() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "build_then_test", temporaryFolder, true);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:nontestclass");
    testResult.assertSuccess("Passing test should exit with 0.");
  }

  /**
   * Test should not be run because the base class is abstract. If the test attempts to run, it will
   * throw a java.lang.InstantiationException.
   */
  @Test
  public void testRunningTestInAbstractClass() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "build_then_test", temporaryFolder, true);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:abstractclass");
    testResult.assertSuccess("Abstract class with test methods should exit with 0.");
  }
}
