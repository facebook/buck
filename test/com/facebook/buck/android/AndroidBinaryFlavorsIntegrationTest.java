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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class AndroidBinaryFlavorsIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "android_project",
        temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testPackageStringAssetsFlavorOutput() throws IOException {
    ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-output",
        "//apps/sample:app_comp_str#package_string_assets");
    String path =
        "buck-out/bin/apps/sample/__strings_app_comp_str#package_string_assets__/";
    result.assertSuccess();
    assertThat(
        result.getStdout().trim().split(" ")[1],
        equalTo(Paths.get(path).toString()));
  }

  @Test
  public void testPackageStringAssetsFlavorDoesNotExist() throws IOException {
    try {
      workspace.runBuckCommand(
          "targets",
          "--show-output",
          "//apps/sample:app#package_string_assets");
      fail("The targets command should have thrown an exception");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("flavor does not exist"));
    }
  }
}
