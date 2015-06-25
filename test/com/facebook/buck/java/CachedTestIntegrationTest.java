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

package com.facebook.buck.java;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

public class CachedTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * This is a regression test. We had a situation where we did the following with the build cache
   * enabled:
   * <ul>
   *   <li>Ran a correct test, which passed (as expected).
   *   <li>Edited the test such that it was incorrect, ran it, and it failed (as expected).
   *   <li>Reverted the change to the test such that the code was correct again, ran it, and it
   *       passed (as expected).
   *   <li>Edited the test such that it was incorrect [in the same way it was incorrect before], ran
   *       it, and it <em>passed</em> when it should have <em>failed</em>.
   * </ul>
   * Note that this behavior was not observed when the build cache was disabled, so this was
   * evidence of a bad interaction between the test runner and the build cache. This integration
   * test reproduces that situation to ensure it does not happen again.
   */
  @Test
  public void testPullingJarFromCacheDoesNotResultInReportingStaleTestResults() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cached_test", tmp);
    workspace.setUp();
    CachedTestUtil testUtil = new CachedTestUtil(workspace);

    // The test should pass out of the box.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");
    result.assertSuccess();

    // Edit the test so it should fail and then make sure that it fails.
    testUtil.makeTestFail();
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test");
    result2.assertTestFailure();
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result2.getStderr(),
        containsString("FAILURE com.example.LameTest testBasicAssertion"));

    // Restore the file to its previous state.
    testUtil.makeTestSucceed();
    ProcessResult result3 = workspace.runBuckCommand("test", "//:test");
    result3.assertSuccess();

    // Put the file back in the broken state and make sure the test fails.
    testUtil.makeTestFail();
    ProcessResult result4 = workspace.runBuckCommand("test", "//:test");
    result4.assertTestFailure();
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result4.getStderr(),
        containsString("FAILURE com.example.LameTest testBasicAssertion"));
  }

  /**
   * This is similar to {@link #testPullingJarFromCacheDoesNotResultInReportingStaleTestResults()}
   * but this first builds the test and then runs it. It catches the corner case where the test run
   * may have the jar read from disk, but the test results are still stale.
   */
  @Test
  public void testRunningTestAfterBuildingWithCacheHitDoesNotReportStaleTests() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cached_test", tmp);
    workspace.setUp();
    CachedTestUtil testUtil = new CachedTestUtil(workspace);

    // The test should pass out of the box.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");
    result.assertSuccess();

    // Edit the test so it should fail and then make sure that it fails.
    testUtil.makeTestFail();
    workspace.runBuckCommand("build", "//:test")
        .assertSuccess("The test should build successfully, but will fail when executed.");
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test");
    result2.assertTestFailure();
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result2.getStderr(),
        containsString("FAILURE com.example.LameTest testBasicAssertion"));

    // Restore the file to its previous state.
    testUtil.makeTestSucceed();
    workspace.runBuckCommand("build", "//:test")
        .assertSuccess("The test should build successfully.");
    ProcessResult result3 = workspace.runBuckCommand("test", "//:test");
    result3.assertSuccess();

    // Put the file back in the broken state and make sure the test fails.
    testUtil.makeTestFail();
    workspace.runBuckCommand("build", "//:test")
        .assertSuccess("The test should build successfully, but will fail when executed.");
    ProcessResult result4 = workspace.runBuckCommand("test", "//:test");
    result4.assertTestFailure();
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result4.getStderr(),
        containsString("FAILURE com.example.LameTest testBasicAssertion"));
  }

  private static class CachedTestUtil {

    private static final String TEST_FILE = "LameTest.java";
    private static final Charset CHARSET = Charsets.UTF_8;
    private final String originalJavaCode;
    private final File testFile;
    private final String failingJavaCode;

    CachedTestUtil(ProjectWorkspace workspace) throws IOException {
      this.testFile = workspace.getFile(TEST_FILE);
      this.originalJavaCode = Files.toString(testFile, CHARSET);
      this.failingJavaCode = originalJavaCode.replace("String str = \"I am not null.\";",
          "String str = null;");
    }

    public void makeTestFail() throws IOException {
      Files.write(failingJavaCode, testFile, CHARSET);
    }

    public void makeTestSucceed() throws IOException {
      Files.write(originalJavaCode, testFile, CHARSET);
    }
  }
}
