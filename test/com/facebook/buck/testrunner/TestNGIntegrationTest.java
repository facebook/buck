/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class TestNGIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testThatSuccessfulTestNGTestWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_testng", temporaryFolder, true);
    workspace.setUp();

    ProcessResult simpleTestNGTestResult = workspace.runBuckCommand("test", "//test:simple-test");
    simpleTestNGTestResult.assertSuccess();
  }

  @Test
  public void testThatFailingTestNGTestWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_testng", temporaryFolder, true);
    workspace.setUp();

    ProcessResult simpleFailingTestNGTestResult =
        workspace.runBuckCommand("test", "//test:simple-failing-test");
    simpleFailingTestNGTestResult.assertTestFailure();
  }

  @Test
  public void testThatInjectionWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_testng", temporaryFolder, true);
    workspace.setUp();

    ProcessResult injectionTestNGTestResult =
        workspace.runBuckCommand("test", "//test:injection-test");
    injectionTestNGTestResult.assertSuccess();
    // Make sure that we didn't just skip over the class.
    assertThat(injectionTestNGTestResult.getStderr(), containsString("1 Passed"));
  }
}
