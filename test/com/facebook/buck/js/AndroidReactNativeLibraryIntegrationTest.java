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

package com.facebook.buck.js;

import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class AndroidReactNativeLibraryIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @BeforeClass
  public static void setupOnce() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
  }

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "android_rn", tmpFolder);
    workspace.setUp();
    filesystem = new ProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testApkContainsJSAssetAndDrawables() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//apps/sample:app");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s.apk")));
    zipInspector.assertFileExists("assets/SampleBundle.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-hdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-xhdpi-v4/image.png");
  }

  @Test
  public void testApkContainsJSAssetAndDrawablesForUnbundle() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//apps/sample:app-unbundle");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s.apk")));
    zipInspector.assertFileExists("assets/SampleBundle.js");
    zipInspector.assertFileExists("assets/js/helpers.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-hdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-xhdpi-v4/image.png");
  }

  @Test
  public void testApkContainsJSAssetAndDrawablesForIndexedUnbundle() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//apps/sample:app-indexed_unbundle");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s.apk")));
    zipInspector.assertFileExists("assets/SampleBundle.js");
    zipInspector.assertFileDoesNotExist("assets/js/helpers.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-hdpi-v4/image.png");
    zipInspector.assertFileExists("res/drawable-xhdpi-v4/image.png");
  }

  @Test
  public void testAaptPackageDependsOnReactNativeBundle() throws IOException {
    workspace.enableDirCache();
    BuildTarget target = BuildTargetFactory.newInstance("//apps/sample:app-without-rn-res");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s.apk")));
    zipInspector.assertFileExists("assets/SampleBundle.js");

    workspace.runBuckCommand("clean");
    workspace.replaceFileContents(
        "apps/sample/BUCK", "#REPLACE_ME_WITH_ANOTHER_RES", "'//res/com/sample/unused:unused'");

    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    zipInspector =
        new ZipInspector(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s.apk")));
    zipInspector.assertFileExists("assets/SampleBundle.js");
  }

  @Test
  public void testEditingUnusedJSFileDoesNotTriggerRebuild() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.replaceFileContents("js/app/unused.js", "anotherFunction", "someOtherFunction");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingDepfileRuleKey("//js:app#bundle,dev");
  }

  @Test
  public void testEditingUnusedJSFileHitsInCache() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "build.depfiles=cache", "//apps/sample:app").assertSuccess();
    workspace.runBuckCommand("clean");

    workspace.replaceFileContents("js/app/unused.js", "anotherFunction", "someOtherFunction");
    workspace.runBuckBuild("-c", "build.depfiles=cache", "//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getLogEntry("//js:app#bundle,dev").getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED)));
  }

  @Test
  public void testEditingUsedJSFileTriggersRebuild() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.replaceFileContents("js/app/helpers.js", "something", "nothing");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//js:app#bundle,dev");
  }

  @Test
  public void testEditingImageRebuildsAndroidResource() throws IOException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    workspace.copyFile("js/app/image@1.5x.png", "js/app/image@2x.png");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//js:app#dev,android_res");
  }
}
