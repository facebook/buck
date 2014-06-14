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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableList;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class AndroidBinaryIntegrationTest {

  public static DebuggableTemporaryFolder projectFolderWithPrebuiltTargets =
      new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";
  private static final String EXOPACKAGE_TARGET = "//apps/multidex:app-exo";

  @BeforeClass
  public static void setUpOnce() throws IOException {
    projectFolderWithPrebuiltTargets.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        new AndroidBinaryIntegrationTest(),
        "android_project",
        projectFolderWithPrebuiltTargets);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build", SIMPLE_TARGET, EXOPACKAGE_TARGET);
    result.assertSuccess();
  }

  @AfterClass
  public static void tearDownLast() {
    projectFolderWithPrebuiltTargets.after();
  }

  @Before
  public void setUp() throws IOException {
    tmpFolder.create();
    workspace = new ProjectWorkspace(projectFolderWithPrebuiltTargets.getRoot(), tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app.apk"));
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }


  @Test
  public void testRawSplitDexHasSecondary() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", RAW_DEX_TARGET);
    result.assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app-art.apk"));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileExists("classes2.dex");


    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }

  @Test
  public void testExopackageHasNoSecondary() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app-exo.apk"));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");

    // It would be better if we could call getExopackageInfo on the app rule.
    Path secondaryDir = workspace.resolve(
        Paths.get(
            "buck-out/bin/apps/multidex/_app-exo#dex_merge_output" +
                "/jarfiles/assets/secondary-program-dex-jars"));

    try (DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(secondaryDir)) {
      List<Path> files = ImmutableList.copyOf(stream);
      assertEquals(1, files.size());
      Path secondaryJar = files.get(0);
      new ZipInspector(secondaryJar.toFile()).assertFileExists("classes.dex");
    }
  }

  @Test
  public void testDisguisedExecutableIsRenamed() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app.apk"));

    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
  }

  @Test
  public void testEditingStringForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/res/values/strings.xml", "Hello", "Bye");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingColorForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/top/res/layout/top_layout.xml", "white", "black");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingImageForcesRebuild() throws IOException {
    workspace.copyFile(
        "res/com/sample/top/res/drawable/tiny_white.png",
        "res/com/sample/top/res/drawable/tiny_something.png");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingAssetForcesRebuild() throws IOException {
    workspace.replaceFileContents("res/com/sample/base/buck-assets/hilarity.txt", "banana", "kiwi");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingManifestForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "apps/multidex/AndroidManifest.xml", "versionCode=\"1\"", "versionCode=\"2\"");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingNativeForcesRebuild() throws IOException, InterruptedException {
    // Sleep 1 second (plus another half to be super duper safe) to make sure that
    // fakesystem.c gets a later timestamp than the fakesystem.o that was produced
    // during the build in setUp.  If we don't do this, there's a chance that the
    // ndk-build we run during the upcoming build will not rebuild it (on filesystems
    // that have 1-second granularity for last modified).
    // To verify this, create a Makefile with the following rule (don't forget to use a tab):
    // out: in
    //   cat $< > $@
    // Run: echo foo > in ; make ; cat out ; echo bar > in ; make ; cat out
    // On a filesystem with 1-second mtime granularity, the last "cat" should print "foo"
    // (with very high probability).
    Thread.sleep(1500);

    ZipInspector zipInspector;


    // Change the binary and ensure that we re-run apkbuilder.
    workspace.replaceFileContents(
        "native/fakenative/jni/fakesystem.c", "exit(status)", "exit(1+status)");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(EXOPACKAGE_TARGET);
    zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app-exo.apk"));
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
    zipInspector.assertFileDoesNotExist("assets/lib/armeabi/libfakenative.so");


    // Now convert it into an asset native library and ensure that we re-run apkbuilder.
    workspace.replaceFileContents(
        "native/fakenative/jni/BUCK",
        "name = 'fakenative',",
        "name = 'fakenative',\nis_asset=True,");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(EXOPACKAGE_TARGET);
    zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app-exo.apk"));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libfakenative.so");
    zipInspector.assertFileExists("assets/lib/armeabi/libfakenative.so");


    // Now edit it again and make sure we re-run apkbuilder.
    Thread.sleep(1500);

    workspace.replaceFileContents(
        "native/fakenative/jni/fakesystem.c", "exit(1+status)", "exit(2+status)");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", EXOPACKAGE_TARGET).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(EXOPACKAGE_TARGET);
    zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app-exo.apk"));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libfakenative.so");
    zipInspector.assertFileExists("assets/lib/armeabi/libfakenative.so");
  }

  @Test
  public void testEditingThirdPartyJarForcesRebuild() throws IOException {
    workspace.copyFile(
        "third-party/kiwi-2.0.jar",
        "third-party/kiwi-current.jar");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingKeystoreForcesRebuild() throws IOException {
    workspace.replaceFileContents(
        "keystores/debug.keystore.properties",
        "my_alias",
        "my_alias\n");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java",
        "package com",
        "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java",
        "package com",
        "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForExopackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java",
        "package com",
        "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(EXOPACKAGE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassGetsAbiHitForExopackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java",
        "package com",
        "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", EXOPACKAGE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetHadMatchingDepsAbi(EXOPACKAGE_TARGET);
  }
}
