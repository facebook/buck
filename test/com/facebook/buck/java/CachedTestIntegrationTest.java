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
    final Charset charsetForTest = Charsets.UTF_8;
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cached_test", tmp);
    workspace.setUp();

    // The test should pass out of the box.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");
    result.assertExitCode(0);

    // Edit the test so it should fail and then make sure that it fails.
    File testFile = workspace.getFile("LameTest.java");
    String originalJavaCode = Files.toString(testFile, charsetForTest);
    String failingJavaCode = originalJavaCode.replace("String str = \"I am not null.\";",
        "String str = null;");
    Files.write(failingJavaCode, testFile, charsetForTest);
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test");
    result2.assertExitCode(1);
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result2.getStderr(),
        containsString("FAILURE testBasicAssertion"));

    // Restore the file to its previous state.
    Files.write(originalJavaCode, testFile, charsetForTest);
    ProcessResult result3 = workspace.runBuckCommand("test", "//:test");
    result3.assertExitCode(0);

    // Put the file back in the broken state and make sure the test fails.
    Files.write(failingJavaCode, testFile, charsetForTest);
    ProcessResult result4 = workspace.runBuckCommand("test", "//:test");
    result4.assertExitCode(1);
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result4.getStderr(),
        containsString("FAILURE testBasicAssertion"));
  }

}
