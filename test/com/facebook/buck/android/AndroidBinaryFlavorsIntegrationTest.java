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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryFlavorsIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testPackageStringAssetsFlavorOutput() throws IOException {
    String target = "//apps/sample:app_comp_str#package_string_assets";
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ProcessResult result = workspace.runBuckCommand("targets", "--show-output", target);
    Path path =
        BuildTargetPaths.getScratchPath(
            filesystem,
            BuildTargetFactory.newInstance(target),
            PackageStringAssets.STRING_ASSETS_DIR_FORMAT);
    result.assertSuccess();
    assertThat(result.getStdout().trim().split(" ")[1], equalTo(path.toString()));
  }

  @Test
  public void testPackageStringsOnlyFlavorOutput() throws IOException {
    String target = "//apps/sample:app_str#package_string_assets";
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ProcessResult result = workspace.runBuckCommand("targets", "--show-output", target);
    Path path =
        BuildTargetPaths.getScratchPath(
            filesystem,
            BuildTargetFactory.newInstance(target),
            PackageStringAssets.STRING_ASSETS_DIR_FORMAT);
    result.assertSuccess();
    assertThat(result.getStdout().trim().split(" ")[1], equalTo(path.toString()));
  }

  @Test
  public void testPackageStringAssetsFlavorDoesNotExist() throws IOException {
    try {
      String target = "//apps/sample:app#package_string_assets";
      workspace.runBuckCommand("targets", "--show-output", target);
      fail("The targets command should have thrown an exception");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("could not be resolved"));
    }
  }
}
