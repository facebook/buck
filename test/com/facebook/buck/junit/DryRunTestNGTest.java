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

package com.facebook.buck.junit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class DryRunTestNGTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_testng", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testShouldFail() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertTestFailure();
    assertThat(result.getStderr(),
        containsString("1 Passed   0 Skipped   1 Failed   com.example.SimpleTest"));
  }

  @Test
  public void dryRunShouldSucceed() throws IOException {
    // Using java_test(test_type='testng', ...), a dry run will visit all test classes
    // but disable all the test methods
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "--all", "--dry-run");
    result.assertSuccess();
    assertThat(result.getStderr(),
        containsString("0 Passed   0 Skipped   0 Failed   com.example.SimpleTest"));
  }
}
