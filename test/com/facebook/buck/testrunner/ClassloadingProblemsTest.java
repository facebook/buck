/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.OutputHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests that exercise how buck behaves when the classloader fails to load/link tests.
 */
public class ClassloadingProblemsTest {

  private ProjectWorkspace workspace;

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "classloading_problems", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void badInstanceMethod() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "//:bad-instance-method");
    result.assertTestFailure("Some tests fail");

    assertThat("Should run test that uses bad class without skipping anything",
        result.getStderr(),
        OutputHelper.containsBuckTestOutputLine(
            "FAIL", null, 0, null,
            "com.example.BadInstanceMethodTest"));
    assertThat("Should not report that the bad class is a bad test",
        result.getStderr(),
        not(OutputHelper.containsBuckTestOutputLine(
            "FAIL", null, null, null, "com.example.BadInstanceMethodImpl")));
    assertThat("Should continue and include the passing test in the output",
        result.getStderr(),
        OutputHelper.containsBuckTestOutputLine(
            "PASS", 1, 0, 0,
            "com.example.PassingTest"));
  }

  @Test
  public void shouldRunTestsWhenHaveBadStaticInitializer() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "//:bad-static-initializer");
    result.assertTestFailure("Some tests fail");

    assertThat("Should run test that uses bad class without skipping anything",
        result.getStderr(),
        OutputHelper.containsBuckTestOutputLine(
            "FAIL", null, 0, null,
            "com.example.BadStaticInitializerTest"));
    assertThat("Should not report that the bad class is a bad test",
        result.getStderr(),
        not(OutputHelper.containsBuckTestOutputLine(
            "FAIL", null, null, null, "com.example.BadStaticInitializerImpl")));
    assertThat("Should continue and include the passing test in the output",
        result.getStderr(),
        OutputHelper.containsBuckTestOutputLine(
            "PASS", 1, 0, 0,
            "com.example.PassingTest"));
  }
}
