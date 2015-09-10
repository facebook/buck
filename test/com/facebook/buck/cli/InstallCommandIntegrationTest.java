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

package com.facebook.buck.cli;

import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import static org.hamcrest.Matchers.is;

import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;

public class InstallCommandIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void appleBundleInstallsInIphoneSimulator() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "install",
        "//:DemoApp");
    result.assertSuccess();

    // TODO(user): If we make the install command output the UDID of the
    // simulator, we could poke around in
    // ~/Library/Developer/CoreSimulator/[UDID] to see if the bits were installed.
  }

  @Test
  public void appleBundleInstallsAndRunsInIphoneSimulator() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "install",
        "-r",
        "//:DemoApp");
    result.assertSuccess();
  }

  @Test
  public void appleBundleInstallsInDevice() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(FakeAppleDeveloperEnvironment.supportsBuildAndInstallToDevice());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    assumeTrue(FakeAppleDeveloperEnvironment.hasDeviceCurrentlyConnected(workspace.getPath(
                "iOSConsole/iOSConsole"
            )));


    ProcessResult result = workspace.runBuckCommand(
        "install",
        "//:DemoApp#iphoneos-arm64");
    result.assertSuccess();
  }
}
