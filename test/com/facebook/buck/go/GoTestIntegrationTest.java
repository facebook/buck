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

package com.facebook.buck.go;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class GoTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
  public ProjectWorkspace workspace;

  @Before
  public void ensureGoIsAvailable() throws IOException, InterruptedException {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "go_test", tmp);
    workspace.setUp();
  }

  @Test
  public void testGoTest() throws IOException {
    // This test should pass.
    ProjectWorkspace.ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    // This test should fail.
    ProjectWorkspace.ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because TestAdd2() failed.",
        result2.getStderr(),
        containsString("TestAdd2"));
  }

  @Test
  public void testGoPanic() throws IOException {
    ProjectWorkspace.ProcessResult result2 = workspace.runBuckCommand("test", "//:test-panic");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because TestPanic() failed.",
        result2.getStderr(),
        containsString("TestPanic"));
  }
}
