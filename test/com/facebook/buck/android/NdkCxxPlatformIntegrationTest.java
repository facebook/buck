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

public class NdkCxxPlatformIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void runtimeSupportsStl() throws IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "runtime_stl", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:main#android-arm").assertSuccess();
    workspace.runBuckCommand("build", "//:main#android-armv7").assertSuccess();
    workspace.runBuckCommand("build", "//:main#android-x86").assertSuccess();
  }

  @Test
  public void changedPlatformTarget() throws IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "ndk_app_platform", tmp);
    workspace.setUp();

    String target = "//:main#android-arm";

    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);

    // Change the app platform and verify that our rulekey has changed.
    workspace.writeContentsToPath("[ndk]\n  app_platform = android-9", ".buckconfig");
    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);
  }
}
