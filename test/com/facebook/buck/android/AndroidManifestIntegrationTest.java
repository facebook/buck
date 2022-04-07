/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidManifestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "android_manifest", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
  }

  @Test
  public void testAndroidManifestIncludesAllDepsFromAndroidResource() throws IOException {
    // Without android.get_all_transitive_android_manifests=true, android_resource rules do not
    // return other android_resource rules as deps that could have an android_manifest, so the
    // build is successful.
    workspace.runBuckBuild("//:android_manifest_with_res_dep").assertSuccess();

    // With the config in place, the build correctly includes an incompatible manifest, so it fails.
    String error =
        workspace
            .runBuckBuild(
                "//:android_manifest_with_res_dep",
                "-c",
                "android.get_all_transitive_android_manifests=true")
            .assertExitCode(ExitCode.BUILD_ERROR)
            .getStderr();
    assertThat(
        error,
        containsString(
            "uses-sdk:minSdkVersion 19 cannot be smaller than version 27 declared in library"));
  }

  @Test
  public void testAndroidManifestIncludesAllDepsFromJavaLibraries() throws IOException {
    // Without android.get_all_transitive_android_manifests=true, java_library rules do not
    // return any deps as deps that could have an android_manifest, so the build is successful.
    workspace.runBuckBuild("//:android_manifest_with_java_library_dep").assertSuccess();

    // With the config in place, the build correctly includes an incompatible manifest, so it fails.
    String error =
        workspace
            .runBuckBuild(
                "//:android_manifest_with_java_library_dep",
                "-c",
                "android.get_all_transitive_android_manifests=true")
            .assertExitCode(ExitCode.BUILD_ERROR)
            .getStderr();
    assertThat(
        error,
        containsString(
            "uses-sdk:minSdkVersion 19 cannot be smaller than version 27 declared in library"));
  }
}
