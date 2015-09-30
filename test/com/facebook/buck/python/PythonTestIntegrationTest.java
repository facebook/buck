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

package com.facebook.buck.python;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.base.Splitter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class PythonTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "python_test", tmp);
    workspace.setUp();
  }

  @Test
  public void testPythonTest() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    // This test should fail.
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because test_that_fails() failed.",
        result2.getStderr(),
        containsString("test_that_fails"));
  }

  @Test
  public void testPythonTestEnv() throws IOException {
    // Test if setting environment during test execution works
    ProcessResult result = workspace.runBuckCommand("test", "//:test-env");
    result.assertSuccess();
  }

  @Test
  public void testPythonSkippedResult() throws IOException, InterruptedException {
    assumePythonVersionIsAtLeast("2.7", "unittest skip support was added in Python-2.7");
    ProcessResult result = workspace.runBuckCommand("test", "//:test-skip").assertSuccess();
    assertThat(result.getStderr(), containsString("1 Skipped"));
  }

  private void assumePythonVersionIsAtLeast(String expectedVersion, String message)
      throws InterruptedException {
    PythonVersion pythonVersion =
        new PythonBuckConfig(new FakeBuckConfig(), new ExecutableFinder())
            .getPythonEnvironment(new ProcessExecutor(new TestConsole()))
            .getPythonVersion();
    String actualVersion = Splitter.on(' ').splitToList(pythonVersion.getVersionString()).get(1);
    assumeTrue(
        String.format(
            "Needs at least Python-%s, but found Python-%s: %s",
            expectedVersion,
            actualVersion,
            message),
        new VersionStringComparator().compare(actualVersion, expectedVersion) >= 0);
  }

}
