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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

public class CachedTestRunReportingIntegrationTest {

  private static final Charset CHARSET_FOR_TEST = Charsets.UTF_8;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * Test that we correctly report which test runs are cached.
   */
  @Test
  public void testCachedTestRun() throws IOException {
    tmp.delete();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cached_test", tmp);
    workspace.setUp();

    // No caching for this run.
      assertFalse(
        "There should not be any caching for the initial test run.",
        isTestRunCached(workspace, true));

    // Cached Results.
    assertTrue(
        "A second test run without any modifications should be cached.",
        isTestRunCached(workspace, true));

    // Make the test fail.
    File testFile = workspace.getFile("LameTest.java");
    String originalJavaCode = Files.toString(testFile, CHARSET_FOR_TEST);
    String failingJavaCode = originalJavaCode.replace("String str = \"I am not null.\";",
        "String str = null;");
    Files.write(failingJavaCode, testFile, CHARSET_FOR_TEST);

    // No caching for this run.
    assertFalse(
        "There should not be any caching for this test run.",
        isTestRunCached(workspace, false));

    // Cached Results.
    assertTrue(
        "A second test run without any modifications should be cached.",
        isTestRunCached(workspace, false));
  }

  private boolean isTestRunCached(ProjectWorkspace workspace, boolean expectSuccess)
      throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:test");
    workspace.verify();
    if (expectSuccess) {
      result.assertSuccess();
    } else {
      result.assertTestFailure();
    }
    // Test that Test status is reported
    assertTrue(result.getStderr().contains("com.example.LameTest"));
    String status = expectSuccess ? "PASS   CACHED" : "FAIL   CACHED";
    return result.getStderr().contains(status);
  }
}
