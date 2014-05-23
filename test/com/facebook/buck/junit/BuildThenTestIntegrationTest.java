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

package com.facebook.buck.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class BuildThenTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  /**
   * It is possible to build a test without running it. It is important to make sure that even
   * though a test built successfully, it knows that it needs to run itself if its results are not
   * available.
   */
  @Test
  public void testBuildThenTest() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "build_then_test", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:example");
    buildResult.assertSuccess("Successful build should exit with 0.");

    ProcessResult testResult = workspace.runBuckCommand("test", "//:example");
    assertEquals("", testResult.getStdout());
    assertTrue(
      "Test output is incorrect:\n=====\n" + testResult.getStderr() + "=====\n",
      testResult.getStderr().contains(
        "TESTING //:example\n" +
        "PASS   <100ms  1 Passed   0 Skipped   0 Failed   com.example.MyTest\n" +
        "TESTS PASSED\n"));
    testResult.assertSuccess("Passing tests should exit with 0.");
    workspace.verify();
  }

  /**
   * Test should pass even when we run tests on non JUnit test classes
   */
  @Test
  public void testRunningTestOnClassWithoutTestMethods() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "build_then_test", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:nontestclass");
    testResult.assertSuccess("Passing test should exit with 0.");
  }

}
