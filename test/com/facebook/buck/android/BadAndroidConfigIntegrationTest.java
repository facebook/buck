/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class BadAndroidConfigIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  /**
   * In this scenario, the local.properties file contains an sdk.dir property that points to a
   * non-existent directory. When a {@code java_library()} rule is built that has no dependency on
   * the Android SDK, the build should succeed even though the Android SDK is misconfigured.
   * <p>
   * However, when an {@code android_library()} rule is built that does depend on the Android SDK,
   * the build should fail, alerting the user to the issue with the local.properties file.
   */
  @Test
  public void testBadAndroidConfigDoesNotInterfereNonAndroidBuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "bad_android_config", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:hello_java").assertSuccess();

    ProcessResult resultForFailure = workspace.runBuckBuild("//:hello_android").assertFailure();
    assertThat(resultForFailure.getStderr(),
        containsString(
            "Properties file local.properties contains invalid path " +
            "[/this/is/a/non/existent/directory] for key sdk.dir."));
  }
}
