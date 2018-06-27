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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidExopackageBinaryIntegrationTest extends AbiCompilationModeTest {

  private static final String DEX_EXOPACKAGE_TARGET = "//apps/multidex:app-dex-exo";
  private static final String NATIVE_EXOPACKAGE_TARGET = "//apps/multidex:app-native-exo";
  private static final String DEX_AND_NATIVE_EXOPACKAGE_TARGET =
      "//apps/multidex:app-dex-native-exo";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidExopackageBinaryIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);

    Properties properties = System.getProperties();
    properties.setProperty(
        "buck.native_exopackage_fake_path",
        Paths.get("assets/android/native-exopackage-fakes.apk").toAbsolutePath().toString());

    workspace
        .runBuckBuild(
            DEX_EXOPACKAGE_TARGET, NATIVE_EXOPACKAGE_TARGET, DEX_AND_NATIVE_EXOPACKAGE_TARGET)
        .assertSuccess();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testDexExopackageHasNoSecondary() throws IOException {
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(DEX_EXOPACKAGE_TARGET), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");

    // It would be better if we could call getExopackageInfo on the app rule.
    Path secondaryDir =
        workspace.resolve(
            BuildTargets.getScratchPath(
                filesystem,
                BuildTargetFactory.newInstance(DEX_EXOPACKAGE_TARGET)
                    .withFlavors(InternalFlavor.of("dex"), InternalFlavor.of("dex_merge")),
                "%s_output/secondary/jarfiles/assets/secondary-program-dex-jars"));

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(secondaryDir)) {
      List<Path> files = Lists.newArrayList(stream);
      assertEquals(2, files.size());
      Collections.sort(files);

      Path secondaryJar = files.get(0);
      ZipInspector zi = new ZipInspector(secondaryJar);
      zi.assertFileExists("classes.dex");
      long jarSize = Files.size(secondaryJar);
      long classesDexSize = zi.getSize("classes.dex");

      Path dexMeta = files.get(1);
      assertEquals(
          String.format("jar:%s dex:%s", jarSize, classesDexSize),
          new String(Files.readAllBytes(dexMeta), StandardCharsets.US_ASCII));
    }
  }

  @Test
  public void testNativeExopackageHasNoNativeLibraries() throws IOException {
    ZipInspector zipInspector =
        new ZipInspector(workspace.getPath("buck-out/gen/apps/multidex/app-native-exo.apk"));

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");

    zipInspector.assertFileExists("classes2.dex");

    zipInspector.assertFileExists("classes.dex");

    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");
  }

  @Test
  public void testDexAndNativeExopackageHasNeitherSecondaryNorNativeLibraries() throws IOException {
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem,
                    BuildTargetFactory.newInstance(DEX_AND_NATIVE_EXOPACKAGE_TARGET),
                    "%s.apk")));

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileDoesNotExist("classes2.dex");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");

    zipInspector.assertFileExists("classes.dex");
  }

  @Test
  public void testEditingStringForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/res/values/strings.xml", "Hello", "Bye");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingColorForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/top/res/layout/top_layout.xml", "white", "black");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingImageForcesRebuild() throws IOException {
    workspace.copyFile(
        "res/com/sample/top/res/drawable/tiny_white.png",
        "res/com/sample/top/res/drawable/tiny_something.png");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingAssetForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/buck-assets/hilarity.txt", "banana", "kiwi");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingManifestForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "apps/multidex/AndroidManifest.xml", "versionCode=\"1\"", "versionCode=\"2\"");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingNativeForcesRebuild() throws IOException {
    ZipInspector zipInspector;

    // Change the binary and ensure that we re-run apkbuilder.
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 4");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
    zipInspector =
        new ZipInspector(workspace.getPath("buck-out/gen/apps/multidex/app-dex-exo.apk"));
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileDoesNotExist("assets/lib/armeabi-v7a/libnative_cxx_lib.so");

    // Now convert it into an asset native library and ensure that we re-run apkbuilder.
    workspace.replaceFileContents(
        "native/cxx/BUCK", "name = \"lib\",", "name = \"lib\",\ncan_be_asset = True,");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
    zipInspector =
        new ZipInspector(workspace.getPath("buck-out/gen/apps/multidex/app-dex-exo.apk"));
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("assets/lib/armeabi-v7a/libnative_cxx_lib.so");

    // Now edit it again and make sure we re-run apkbuilder.
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 4", "return 5");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
    zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(DEX_EXOPACKAGE_TARGET), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("assets/lib/armeabi-v7a/libnative_cxx_lib.so");
  }

  @Test
  public void testEditingNativeGetsRuleKeyHitForNativeExopackage() throws IOException {
    // Change the binary and ensure that we re-run apkbuilder.
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild("-v=5", NATIVE_EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetHadMatchingRuleKey(NATIVE_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingNativeAndSecondaryDexFileGetsAbiHitForDexAndNativeExopackage()
      throws IOException {
    // Change the binary and ensure that we re-run apkbuilder.
    workspace.replaceFileContents("native/cxx/lib.cpp", "return 3", "return 7");

    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_AND_NATIVE_EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(DEX_AND_NATIVE_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingThirdPartyJarForcesRebuild() throws IOException {
    workspace.copyFile("third-party/kiwi-2.0.jar", "third-party/kiwi-current.jar");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingKeystoreForcesRebuild() throws IOException {
    workspace.replaceFileContents("keystores/debug.keystore.properties", "my_alias", "my_alias\n");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForExopackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForNativeExopackage() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(NATIVE_EXOPACKAGE_TARGET).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(NATIVE_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassGetsAbiHitForExopackage() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_EXOPACKAGE_TARGET).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetHadMatchingInputRuleKey(DEX_EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassGetsAbiHitForDexAndNativeExopackage() throws IOException {
    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(DEX_AND_NATIVE_EXOPACKAGE_TARGET).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetHadMatchingInputRuleKey(DEX_AND_NATIVE_EXOPACKAGE_TARGET);
  }
}
