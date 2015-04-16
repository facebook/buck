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

package com.facebook.buck.d;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

public class DTestIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void failingTest() throws Exception {
    Assumptions.assumeDCompilerAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:failing_test");
    result.assertTestFailure();
    assertTrue(
        "test reports correct location on failure. stderr:\n" + result.getStderr(),
        result.getStderr().matches("(?s:.*)failing_test(?:\\.d)?\\(4\\)(?s:.*)"));
  }

  @Test
  public void passingTest() throws Exception {
    Assumptions.assumeDCompilerAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "test", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:passing_test");
    result.assertSuccess();
  }
}
