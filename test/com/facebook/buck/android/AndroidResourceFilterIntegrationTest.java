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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.testutil.integration.ApkInspector;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class AndroidResourceFilterIntegrationTest {

  private static final String APK_PATH_FORMAT = "buck-out/gen/apps/sample/%s.apk";

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    tmpFolder.create();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testApkWithoutResourceFilter() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app"));
    ApkInspector apkInspector = new ApkInspector(apkFile);

    apkInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
    apkInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
    apkInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
  }

  @Test
  public void testApkWithMdpiFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_mdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    ApkInspector apkInspector = new ApkInspector(apkFile);

    apkInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
    apkInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
    apkInspector.assertFileDoesNotExist("res/drawable-xhdpi/app_icon.png");
  }

  @Test
  public void testModifyingImageRebuildsResourcesFilter() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//apps/sample:app_mdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    long firstImageCrc = new ApkInspector(apkFile).getCrc("res/drawable-mdpi/app_icon.png");

    workspace.copyFile(
        "res/com/sample/base/res/drawable-hdpi/app_icon.png",
        "res/com/sample/base/res/drawable-mdpi/app_icon.png");

    workspace.resetBuildLogFile();
    result = workspace.runBuckBuild("//apps/sample:app_mdpi");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/sample:app_mdpi");

    apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    long secondImageCrc = new ApkInspector(apkFile).getCrc("res/drawable-mdpi/app_icon.png");

    assertNotEquals(firstImageCrc, secondImageCrc);
  }

  @Test
  public void testApkWithXhdpiAndHdpiFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_hdpi_xhdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_hdpi_xhdpi"));
    ApkInspector apkInspector = new ApkInspector(apkFile);

    apkInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
    apkInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
    apkInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
  }

  @Test
  public void testApkWithStringsAsAssets() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_comp_str");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_comp_str"));
    ApkInspector apkInspector = new ApkInspector(apkFile);

    apkInspector.assertFileExists("assets/strings/fr.fbstr");
  }

  @Test
  public void testStringArtifactsAreCached() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    Sha1HashCode androidBinaryRuleKey = buildLog.getRuleKey("//apps/sample:app_comp_str");
    File cachedFile = workspace.getFile("buck-cache/" + androidBinaryRuleKey.getHash());
    assertTrue(cachedFile.delete());

    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
  }

  @Test
  public void testApkWithStringsAsAssetsAndResourceFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckBuild("//apps/sample:app_comp_str_xhdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_comp_str_xhdpi"));
    ApkInspector apkInspector = new ApkInspector(apkFile);

    apkInspector.assertFileExists("assets/strings/fr.fbstr");
    apkInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
    apkInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
    apkInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
  }

  @Test
  public void testAsset() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    String apkFilePath = String.format(APK_PATH_FORMAT, "app");

    File apkFile = workspace.getFile(apkFilePath);
    ApkInspector apkInspector = new ApkInspector(apkFile);

    long firstCrc = apkInspector.getCrc("assets/asset_file.txt");

    workspace.replaceFileContents(
        "res/com/sample/asset_only/assets/asset_file.txt",
        "Hello",
        "Bye");
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    apkFile = workspace.getFile(apkFilePath);
    apkInspector = new ApkInspector(apkFile);

    long secondCrc = apkInspector.getCrc("assets/asset_file.txt");

    assertNotEquals("Rebuilt APK file must include the new asset file.", firstCrc, secondCrc);
  }
}
