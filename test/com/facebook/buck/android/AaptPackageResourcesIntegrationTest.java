/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.zip.ZipConstants;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AaptPackageResourcesIntegrationTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;

  private static final String MAIN_BUILD_TARGET = "//apps/sample:app";

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testIgnoredFileIsIgnoredByAapt() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//apps/sample:app_deps_resource_with_ignored_file").assertSuccess();
  }

  @Test
  public void testAaptPackageIsScrubbed() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild(MAIN_BUILD_TARGET).assertSuccess();
    Path aaptOutput =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(MAIN_BUILD_TARGET)
                    .withFlavors(AndroidBinaryResourcesGraphEnhancer.AAPT_PACKAGE_FLAVOR),
                AaptPackageResources.RESOURCE_APK_PATH_FORMAT));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(aaptOutput.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }
}
