/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import java.io.IOException;

/**
 * Wraps {@link TestDataHelper#createProjectWorkspaceForScenario} for Android integration tests,
 * adding support for dynamically configuring the build-tools and compile_sdk versions.
 */
final class AndroidProjectWorkspace {
  private AndroidProjectWorkspace() {}

  /**
   * Creates a project workspace for Android integration tests. If the following environment
   * variables are set, then the equivalent Buck properties will be set automatically in the project
   * workspace:
   *
   * <ul>
   *   <li><code>BUCK_TEST_ANDROID_BUILD_TOOLS_VERSION</code>: android.build_tools_version
   *   <li><code>BUCK_TEST_ANDROID_COMPILE_SDK_VERSION</code>: android.compile_sdk_version
   * </ul>
   */
  static ProjectWorkspace create(Object testCase, String scenario, TemporaryPaths tempFolder)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(testCase, scenario, tempFolder);
    addConfigOptionFromEnvironment(
        workspace, "BUCK_TEST_ANDROID_BUILD_TOOLS_VERSION", "build_tools_version");
    addConfigOptionFromEnvironment(
        workspace, "BUCK_TEST_ANDROID_COMPILE_SDK_VERSION", "compile_sdk_version");

    return workspace;
  }

  private static void addConfigOptionFromEnvironment(
      ProjectWorkspace workspace, String env, String key) throws IOException {

    String value = EnvVariablesProvider.getSystemEnv().get(env);
    if (value != null) {
      workspace.addBuckConfigLocalOption("android", key, value);
    }
  }
}
