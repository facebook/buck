/*
 * Copyright 2019-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryExopackageSymlinkTreeIntegrationTest {
  private static final String FULL_EXOPACKAGE_SYMLINK_TREE_TARGET =
      "//apps/multidex:app-full-exo#exo_symlink_tree";
  private static final String DEX_EXOPACKAGE_SYMLINK_TREE_TARGET =
      "//apps/multidex:app-dex-exo#exo_symlink_tree";
  private static final String NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET =
      "//apps/multidex:app-native-exo#exo_symlink_tree";

  private ProjectWorkspace workspace;
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private Path fullExoOutputPath;
  private Path dexExoOutputPath;
  private Path nativeExoOutputPath;

  @Before
  public void setUp() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryExopackageSymlinkTreeIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();

    Properties properties = System.getProperties();
    properties.setProperty(
        "buck.native_exopackage_fake_path",
        Paths.get("assets/android/native-exopackage-fakes.apk").toAbsolutePath().toString());

    fullExoOutputPath = workspace.buildAndReturnOutput(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
    dexExoOutputPath = workspace.buildAndReturnOutput(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET);
    nativeExoOutputPath = workspace.buildAndReturnOutput(NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testFullExopackageSymlinkTreeCreated() {
    assertTrue(Files.exists(fullExoOutputPath));
    assertTrue(Files.exists(fullExoOutputPath.resolve("metadata.txt")));
    assertTrue(Files.exists(fullExoOutputPath.resolve("secondary-dex")));
    assertTrue(Files.exists(fullExoOutputPath.resolve("native-libs")));
    assertTrue(Files.exists(fullExoOutputPath.resolve("resources")));
  }

  @Test
  public void testFullExoEditingNativeForcesRebuild() throws IOException {
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testFullExoEditingSecondaryDexClassForcesRebuild() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testFullExoEditingResourcesForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/res/values/strings.xml", "Hello", "Bye");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testFullExoEditingManifestForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "apps/multidex/AndroidManifest.xml", "versionCode=\"1\"", "versionCode=\"2\"");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testFullExoEditingPrimaryDexClassForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(FULL_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testDexExopackageSymlinkTreeCreated() {
    assertTrue(Files.exists(dexExoOutputPath));
    assertTrue(Files.exists(dexExoOutputPath.resolve("metadata.txt")));
    assertTrue(Files.exists(dexExoOutputPath.resolve("secondary-dex")));
    assertFalse(Files.exists(dexExoOutputPath.resolve("native-libs")));
    assertFalse(Files.exists(dexExoOutputPath.resolve("resources")));
  }

  @Test
  public void testDexExoEditingNativeGetsRuleKeyHit() throws IOException {
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET);
    workspace.getBuildLog().assertTargetHadMatchingRuleKey(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testDexExoEditingSecondaryDexClassForcesRebuild() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testDexExoEditingPrimaryDexClassForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(DEX_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testNativeExopackageSymlinkTreeCreated() {
    assertTrue(Files.exists(nativeExoOutputPath));
    assertTrue(Files.exists(nativeExoOutputPath.resolve("metadata.txt")));
    assertFalse(Files.exists(nativeExoOutputPath.resolve("secondary-dex")));
    assertTrue(Files.exists(nativeExoOutputPath.resolve("native-libs")));
    assertFalse(Files.exists(nativeExoOutputPath.resolve("resources")));
  }

  @Test
  public void testNativeExoEditingNativeForcesRebuild() throws IOException {
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET);
    workspace.getBuildLog().assertTargetBuiltLocally(NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }

  @Test
  public void testNativeExoEditingDexClassGetsRuleKeyHit() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");
    workspace.resetBuildLogFile();
    workspace.runBuckBuild(NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey(NATIVE_EXOPACKAGE_SYMLINK_TREE_TARGET);
  }
}
