/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.distributed.DistBuildIntegrationTest.FrontendServer;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildAppleIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() {
    Assume.assumeTrue(Platform.detect() == Platform.MACOS);
    Assume.assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void canBuildAppleBundles() throws Exception {
    Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path destinationFolderPath = temporaryFolder.newFolder("destination");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");

    String scenario = "apple_bundle";
    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, sourceFolderPath);
    sourceWorkspace.setUp();

    sourceWorkspace
        .runBuckBuild(
            "//:DemoApp#iphonesimulator-x86_64,no-debug",
            "--distributed",
            "--build-state-file",
            stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    FrontendServer.runDistBuildWithFakeFrontend(
            destinationWorkspace,
            "--build-state-file",
            stateFilePath.toString(),
            "--buildslave-run-id",
            "i_am_slave_with_run_id_42")
        .assertSuccess();
  }
}
