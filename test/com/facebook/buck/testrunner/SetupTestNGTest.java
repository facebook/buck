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

package com.facebook.buck.testrunner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests setup failing for testng runs.
 */
public final class SetupTestNGTest {

  private ProjectWorkspace workspace;

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_testng_setup", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testShouldFailIfSetupFails() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure();
    assertThat(
        result.getStderr(),
        containsString("0 Passed   0 Skipped   1 Failed   com.example.SimpleTest"));
  }

  @Test
  public void testShouldCaptureOutOfBeforeTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure();
    assertThat(result.getStderr(), containsString("some output in the setUp method"));
  }

  @Test
  public void testShouldCaptureExceptionOfBeforeTest() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure();
    assertThat(result.getStderr(), containsString("java.lang.Exception: Failure"));
  }
}
