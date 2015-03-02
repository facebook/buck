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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class PreDexMergeIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  private static final String MAIN_BUILD_TARGET = "//apps/multidex:app";
  private static final String PRIMARY_SOURCE_FILE = "java/com/sample/app/MyApplication.java";
  private static final String SECONDARY_SOURCE_FILE = "java/com/sample/lib/Sample.java";
  private static final String PRIMARY_HASH_PATH =
      "buck-out/bin/apps/multidex/.app#dex_merge/metadata/primary_dex_hash";

  @Before
  public void setUp() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);

    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();
  }

  @Test
  public void testEditingPrimaryDexClassChangesHash() throws IOException {
    // This is too low-level of a test.  Ideally, we'd be able to save the rule graph generated
    // by the build and query it directly, but runBuckCommand doesn't support that, so just
    // test the files directly for now.
    String firstHash = workspace.getFileContents(PRIMARY_HASH_PATH);

    workspace.replaceFileContents(PRIMARY_SOURCE_FILE, "package com", "package\ncom");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    String secondHash = workspace.getFileContents(PRIMARY_HASH_PATH);

    assertTrue(firstHash.matches("\\p{XDigit}{40}"));
    assertTrue(secondHash.matches("\\p{XDigit}{40}"));
    assertNotEquals(firstHash, secondHash);
  }

  @Test
  public void testEditingSecondaryDexClassDoesNotChangeHash() throws IOException {
    // This is too low-level of a test.  Ideally, we'd be able to save the rule graph generated
    // by the build and query it directly, but runBuckCommand doesn't support that, so just
    // test the files directly for now.
    String firstHash = workspace.getFileContents(PRIMARY_HASH_PATH);

    workspace.replaceFileContents(SECONDARY_SOURCE_FILE, "package com", "package\ncom");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", MAIN_BUILD_TARGET);
    result.assertSuccess();

    String secondHash = workspace.getFileContents(PRIMARY_HASH_PATH);

    assertTrue(firstHash.matches("\\p{XDigit}{40}"));
    assertTrue(secondHash.matches("\\p{XDigit}{40}"));
    assertEquals(firstHash, secondHash);
  }

}
