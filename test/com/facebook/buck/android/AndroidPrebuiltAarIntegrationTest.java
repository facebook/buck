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
import com.facebook.buck.testutil.integration.ZipInspector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidPrebuiltAarIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void setUp() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "android_prebuilt_aar",
        tmp);
    workspace.setUp();
  }

  @Test
  public void testBuildAndroidPrebuiltAar() throws IOException {
    workspace.runBuckBuild("//:app").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(workspace.getFile("buck-out/gen/app.apk"));
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("resources.arsc");
    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/x86/liba.so");
  }

  @Test
  public void testProjectAndroidPrebuiltAar() throws IOException {
    workspace.runBuckCommand("project", "//:app").assertSuccess();
  }
}
