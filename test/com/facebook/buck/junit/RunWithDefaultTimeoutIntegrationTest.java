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

package com.facebook.buck.junit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.internal.builders.AnnotatedBuilder;
import org.junit.internal.builders.JUnit4Builder;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

import java.io.IOException;

public class RunWithDefaultTimeoutIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  /**
   * This test verifies that the default timeout declared in {@code .buckconfig} works in the
   * presence of the {@link RunWith} annotation.
   * <p>
   * We implement support for a default timeout declared in {@code .buckconfig} by implementing our
   * own {@link BlockJUnit4ClassRunner}, {@link BuckBlockJUnit4ClassRunner}. We have tweaked our
   * JUnit runner to use a {@link JUnit4Builder} that creates a {@link BuckBlockJUnit4ClassRunner}
   * whenever a {@link JUnit4Builder} is requested.
   * <p>
   * However, we have a problem when the {@link RunWith} annotation is used. When {@link RunWith} is
   * present, JUnit requests an {@link AnnotatedBuilder} instead of a {@link JUnit4Builder}.
   * Because Robolectric requires the use of {@link RunWith}, this situation is common in Android
   * testing.
   * <p>
   * To circumvent this issue, when possible, we create our own {@link AnnotatedBuilder} that
   * delegates to the original {@link AnnotatedBuilder}, but inserts the timeout logic that we need.
   */
  @Test
  public void testRunWithHonorsDefaultTimeoutOnTestThatRunsLong() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "run_with_timeout", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:TestThatTakesTooLong");
    testResult.assertTestFailure("Should fail due to exceeding timeout.");
    assertThat(testResult.getStderr(), containsString("timed out after 3000 milliseconds"));
  }

  @Test
  public void testRunWithHonorsDefaultTimeoutOnTestThatRunsForever() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "run_with_timeout", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:TestThatRunsForever");
    testResult.assertTestFailure("Should fail due to exceeding timeout.");
    assertThat(testResult.getStderr(), containsString("timed out after 3000 milliseconds"));
  }

  @Test
  public void testRunWithLetsTimeoutAnnotationOverrideDefaultTimeout() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "run_with_timeout", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test",
        "//:TestThatExceedsDefaultTimeoutButIsLessThanTimeoutAnnotation");
    testResult.assertSuccess();
  }

  @Test
  public void testRunWithLetsTimeoutRuleOverrideDefaultTimeout() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "run_with_timeout", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test",
        "//:TestThatExceedsDefaultTimeoutButIsLessThanTimeoutRule");
    testResult.assertSuccess();
  }

  @Test
  public void testAllTestsForRunWithAreRunOnTheSameThread() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "run_with_timeout", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test",
        "//:MultipleTestsThatExpectToBeAbleToReuseTheMainThread");
    testResult.assertSuccess();
  }
}
