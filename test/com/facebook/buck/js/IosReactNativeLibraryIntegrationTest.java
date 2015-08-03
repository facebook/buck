/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.js;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IosReactNativeLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @BeforeClass
  public static void setupOnce() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "ios_rn", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testBundleOutputContainsJSAndResources() throws IOException {
    workspace.runBuckBuild("//:DemoApp#iphonesimulator-x86_64").assertSuccess();

    workspace.verify();
  }

  @Test
  public void testFlavoredBundleOutputDoesNotContainJSAndResources() throws IOException {
    workspace.runBuckBuild("//:DemoApp#iphonesimulator-x86_64,rn_no_bundle").assertSuccess();

    Path appDir = workspace.getPath(
        "buck-out/gen/DemoApp#iphonesimulator-x86_64,rn_no_bundle/DemoApp.app");
    assertTrue(Files.isDirectory(appDir));

    Path bundle = appDir.resolve("Apps/DemoApp/DemoApp.bundle");
    assertFalse(Files.exists(bundle));
  }
}
