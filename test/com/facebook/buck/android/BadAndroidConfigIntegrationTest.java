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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class BadAndroidConfigIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  /**
   * In this scenario, the {@code ANDROID_SDK} environment variable points to a non-existent
   * directory. When a {@code java_library()} rule is built that has no dependency on the Android
   * SDK, the build should succeed even though the Android SDK is misconfigured.
   *
   * <p>However, when an {@code android_library()} rule is built that does depend on the Android
   * SDK, the build should fail, alerting the user to the issue.
   */
  @Test
  public void testBadAndroidConfigDoesNotInterfereNonAndroidBuild() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "bad_android_config", tmp);
    workspace.setUp();
    ImmutableMap<String, String> badEnvironment =
        ImmutableMap.of("ANDROID_SDK", "/this/directory/does/not/exist");
    workspace.runBuckCommand(badEnvironment, "build", "//:hello_java").assertSuccess();

    ProcessResult processResult =
        workspace.runBuckCommand(badEnvironment, "build", "//:hello_android");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        allOf(
            containsString("BUILD FAILED"),
            containsString("'ANDROID_SDK'"),
            anyOf(
                containsString("'/this/directory/does/not/exist'"),
                containsString("'\\this\\directory\\does\\not\\exist'"))));
  }
}
