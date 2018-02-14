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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class NdkLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp1 = new TemporaryPaths();

  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();

  @Test
  public void cxxLibraryDep() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace1 =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp1);
    workspace1.setUp();
    workspace1.runBuckBuild("//jni:foo").assertSuccess();

    ProjectWorkspace workspace2 =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp2);
    workspace2.setUp();
    workspace2.runBuckBuild("//jni:foo").assertSuccess();

    // Verify that rule keys generated from building in two different working directories
    // does not affect the rule key.
    assertNotEquals(workspace1.resolve(Paths.get("test")), workspace2.resolve(Paths.get("test")));
    assertEquals(
        workspace1.getBuildLog().getRuleKey("//jni:foo"),
        workspace2.getBuildLog().getRuleKey("//jni:foo"));
  }

  @Test
  public void sourceFilesChangeTargetHash() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp1);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "//jni:foo", "--show-target-hash", "--target-hash-file-mode=PATHS_ONLY");
    result.assertSuccess();
    String[] targetAndHash = result.getStdout().trim().split("\\s+");
    assertEquals("//jni:foo", targetAndHash[0]);
    String hashBefore = targetAndHash[1];

    ProcessResult result2 =
        workspace.runBuckCommand(
            "targets",
            "//jni:foo",
            "--show-target-hash",
            "--target-hash-file-mode=PATHS_ONLY",
            "--target-hash-modified-paths=" + workspace.resolve("jni/foo.cpp"));

    result2.assertSuccess();
    String[] targetAndHash2 = result2.getStdout().trim().split("\\s+");
    assertEquals("//jni:foo", targetAndHash2[0]);
    String hashAfter = targetAndHash2[1];

    assertNotEquals(hashBefore, hashAfter);
  }

  @Test
  public void ndkLibraryOwnsItsSources() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp1);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query", String.format("owner(%s)", workspace.resolve("jni/foo.cpp")));
    result.assertSuccess();
    assertEquals("//jni:foo", result.getStdout().trim());
  }
}
