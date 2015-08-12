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

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidReactNativeLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @BeforeClass
  public static void setupOnce() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_rn", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testApkContainsJSAssetAndDrawables() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/apps/sample/app.apk"));
    zipInspector.assertFileExists("assets/SampleBundle.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-hdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-xhdpi-v4/image.png");
  }

  @Test
  public void testAaptPackageDependsOnReactNativeBundle() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app-without-rn-res").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/apps/sample/app-without-rn-res.apk"));
    zipInspector.assertFileExists("assets/SampleBundle.js");

    workspace.runBuckCommand("clean");
    workspace.replaceFileContents(
        "apps/sample/BUCK",
        "#REPLACE_ME_WITH_ANOTHER_RES",
        "'//res/com/sample/unused:unused'");

    workspace.runBuckBuild("//apps/sample:app-without-rn-res").assertSuccess();
    zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/apps/sample/app-without-rn-res.apk"));
    zipInspector.assertFileExists("assets/SampleBundle.js");
  }

  @Test
  public void testEditingUnusedJSFileDoesNotTriggerRebuild() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.replaceFileContents("js/app/unused.js", "anotherFunction", "someOtherFunction");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//js:app#dev,rn_deps");
    buildLog.assertTargetHadMatchingDepsAbi("//js:app#bundle,dev");
  }

  @Test
  public void testEditingUsedJSFileTriggersRebuild() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.replaceFileContents("js/app/helpers.js", "something", "nothing");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//js:app#dev,rn_deps");
    buildLog.assertTargetBuiltLocally("//js:app#bundle,dev");
  }

  @Test
  public void testEditingImageRebuildsAndroidResource() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.copyFile("js/app/image@1.5x.png", "js/app/image@2x.png");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//js:app#dev,rn_deps");
    buildLog.assertTargetBuiltLocally("//js:app#dev,android_res");
  }
}
