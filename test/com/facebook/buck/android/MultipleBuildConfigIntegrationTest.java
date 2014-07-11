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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class MultipleBuildConfigIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * Regression test for https://github.com/facebook/buck/issues/187.
   */
  @Test
  public void testAndroidBinarySupportsMultipleBuildConfigs() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//java/com/buildconfigs:extract-classes-dex")
        .assertSuccess();

    String smali = workspace.getFileContents("buck-out/gen/java/com/buildconfigs/smali-files.txt");
    assertThat(smali, containsString("com/example/config1/BuildConfig.smali"));
    assertThat(smali, containsString("com/example/config2/BuildConfig.smali"));
  }
}
