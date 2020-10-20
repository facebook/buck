/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Properties;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AndroidFullExopackageBinaryIntegrationTest {
  private static final String RESOURCES_EXOPACKAGE_TARGET = "//apps/multidex:app-full-exo";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Parameterized.Parameters(name = "should_execute_in_separate_process={0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(ImmutableList.of(false, true));
  }

  @Parameterized.Parameter() public boolean shouldExecuteInSeparateProcess;

  private ProjectWorkspace workspace;
  private Path outputPath;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidFullExopackageBinaryIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();

    Properties properties = System.getProperties();
    properties.setProperty(
        "buck.native_exopackage_fake_path",
        Paths.get("assets/android/native-exopackage-fakes.apk").toAbsolutePath().toString());

    if (shouldExecuteInSeparateProcess) {
      workspace.enableOutOfProcessExecution();
      URL binary =
          Resources.getResource("com/facebook/buck/external/main/external_actions_bin.jar");
      Path externalBinary = tmpFolder.getRoot().getPath().resolve("external_action.jar");
      try (FileOutputStream stream = new FileOutputStream(externalBinary.toFile())) {
        stream.write(Resources.toByteArray(binary));
      }
      properties.setProperty("buck.external_actions", externalBinary.toString());
    }

    outputPath = workspace.buildAndReturnOutput(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testApkHasABunchOfThingsNotIncluded() throws IOException {
    ZipInspector zipInspector = new ZipInspector(outputPath);

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileDoesNotExist("classes2.dex");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileDoesNotExist("assets/hilarity.txt");

    zipInspector.assertFileExists("classes.dex");
  }

  @Test
  public void testEditingNativeGetsRuleKeyHit() throws IOException {
    // Change the binary and ensure that we re-run apkbuilder.
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassGetsAbiHit() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingAssetGetsAbiHit() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/buck-assets/hilarity.txt", "banana", "kiwi");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingStringGetsAbiHit() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/res/values/strings.xml", "Hello", "Bye");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingColorGetsAbiHit() throws IOException {
    workspace.replaceFileContents("res/com/sample/top/res/layout/top_layout.xml", "white", "black");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingImageGetsAbiHit() throws IOException {
    workspace.copyFile(
        "res/com/sample/top/res/drawable/tiny_white.png",
        "res/com/sample/top/res/drawable/tiny_something.png");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingManifestReferencedStringForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "res/com/sample/base/res/values/strings.xml", "Sample App", "Exo App");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  @Ignore
  public void testEditingManifestReferencedImageForcesRebuild() throws IOException {
    workspace.copyFile(
        "res/com/sample/top/res/drawable/tiny_white.png",
        "res/com/sample/top/res/drawable/app_icon.png");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingManifestForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "apps/multidex/AndroidManifest.xml", "versionCode=\"1\"", "versionCode=\"2\"");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingThirdPartyJarForcesRebuild() throws IOException {
    workspace.copyFile("third-party/kiwi-2.0.jar", "third-party/kiwi-current.jar");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingKeystoreForcesRebuild() throws IOException {
    workspace.replaceFileContents("keystores/debug.keystore.properties", "my_alias", "my_alias\n");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "MyReplaceableName", "MyNewReplaceableName");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(RESOURCES_EXOPACKAGE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(RESOURCES_EXOPACKAGE_TARGET);
  }
}
