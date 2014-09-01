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

package com.facebook.buck.java;


import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

@Ignore("Unable to run as part of Buck because both hamcrest and junit are present")
public class JavaTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void shouldRefuseToRunJUnitTestsIfHamcrestNotOnClasspath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "missing_test_deps",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:no-hamcrest");

    // The bug this addresses was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate hamcrest on the classpath. Please add as a test dependency."));
  }

  @Test
  public void shouldRefuseToRunJUnitTestsIfJUnitNotOnClasspath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "missing_test_deps",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:no-junit");

    // The bug this address was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate junit on the classpath. Please add as a test dependency."));
  }
}
