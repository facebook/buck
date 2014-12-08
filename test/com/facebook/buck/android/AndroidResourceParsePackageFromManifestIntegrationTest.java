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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidResourceParsePackageFromManifestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * If {@code res} and {@code manifest} are specified for an {@code android_resource}, but
   * {@code package} is not, then the package for the generated {@code R.java} file should be
   * extracted from the {@code AndroidManifest.xml} file.
   * <p>
   * We verify this by creating such an {@code android_resource} rule, and then ensure that an
   * {@code android_library} that depends on it builds successfully. The Java code in the
   * {@code android_library} contains references to {@code com.example.R} to ensure that the
   * {@code com.example} package was extracted from the manifest correctly.
   */
  @Test
  public void testParsePackageFromManifest() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "parse_package_from_manifest",
        tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:library").assertSuccess();

    // Modify ExampleActivity.java and rebuild //:library. This will verify that //:res is hydrated
    // correctly when read from cache.
    String javaCode = workspace.getFileContents("ExampleActivity.java");
    javaCode = javaCode.replace("app_name", "app_name2");
    workspace.writeContentsToPath(javaCode, "ExampleActivity.java");
    workspace.runBuckBuild("//:library").assertSuccess();
  }
}
