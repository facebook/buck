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

package com.facebook.buck.features.d;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import org.junit.Rule;
import org.junit.Test;

public class DTestIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void failingTest() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "-v", "10", "//:failing_test");
    result.assertTestFailure();
    assertTrue(
        "test reports correct location on failure. stderr:\n" + result.getStderr(),
        result.getStderr().matches("(?s:.*)failing_test(?:\\.d)?\\(4\\)(?s:.*)"));
  }

  @Test
  public void passingTest() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "-v", "10", "//:passing_test");
    result.assertSuccess();
  }

  @Test
  public void testDTestTimeout() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "-v", "10", "//:test-spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("Timed out after 500 ms running test command"));
  }

  @Test
  public void withCxx() throws Exception {
    Assumptions.assumeDCompilerUsable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "-v", "10", "//:with_cxx");
    result.assertSuccess();
  }
}
