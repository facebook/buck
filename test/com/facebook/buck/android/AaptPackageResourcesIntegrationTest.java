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

import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AaptPackageResourcesIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  private static final String MAIN_BUILD_TARGET = "//apps/sample:app";
  private static final String PATH_TO_LAYOUT_XML = "res/com/sample/top/res/layout/top_layout.xml";

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testEditingLayoutChangesPackageHash() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild(MAIN_BUILD_TARGET).assertSuccess();

    // This is too low-level of a test.  Ideally, we'd be able to save the rule graph generated
    // by the build and query it directly, but runBuckCommand doesn't support that, so just
    // test the files directly for now.
    String firstHash = workspace.getFileContents(
        "buck-out/bin/apps/sample/.app#aapt_package/metadata/resource_package_hash");

    workspace.replaceFileContents(PATH_TO_LAYOUT_XML, "white", "black");

    workspace.runBuckBuild(MAIN_BUILD_TARGET).assertSuccess();

    String secondHash = workspace.getFileContents(
        "buck-out/bin/apps/sample/.app#aapt_package/metadata/resource_package_hash");

    Sha1HashCode firstHashCode = ImmutableSha1HashCode.of(firstHash);
    Sha1HashCode secondHashCode = ImmutableSha1HashCode.of(secondHash);
    assertNotEquals(firstHashCode, secondHashCode);
  }

  @Test
  public void testOrigFileIsIgnoredByAapt() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//apps/sample:app_deps_resource_with_orig_file").assertSuccess();
  }
}
