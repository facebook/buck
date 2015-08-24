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

package com.facebook.buck.apple;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ApplePackageIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void packageHasProperStructure() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:DemoApp");

    workspace.runBuckCommand("clean").assertSuccess();

    workspace.runBuckCommand("build", "//:DemoAppPackage").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:DemoApp");
    workspace.getBuildLog().assertTargetBuiltLocally("//:DemoAppPackage");

    Path templateDir = TestDataHelper.getTestDataScenario(this, "simple_application_bundle");

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/DemoAppPackage.ipa"));
    zipInspector.assertFileExists(("Payload/DemoApp.app/DemoApp"));
    zipInspector.assertFileContents("Payload/DemoApp.app/PkgInfo", new String(Files.readAllBytes(
            templateDir.resolve(
                "buck-out/gen/DemoApp#iphonesimulator-x86_64/DemoApp.app/PkgInfo.expected")),
            UTF_8));

  }
}
