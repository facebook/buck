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

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AndroidBinaryIntegrationTest {

  @ClassRule
  public static DebuggableTemporaryFolder projectFolderWithPrebuiltTargets =
      new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";

  @BeforeClass
  public static void setUpOnce() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        new AndroidBinaryIntegrationTest(),
        "android_project",
        projectFolderWithPrebuiltTargets);
    workspace.setUp();
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
  }

  @AfterClass
  public static void tearDownLast() {
    projectFolderWithPrebuiltTargets.after();
  }

  @Before
  public void setUp() throws IOException {
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
  public void testDisguisedExecutableIsRenamed() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/multidex/app.apk"));

    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
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
  public void testPreprocessorForcesReDex() throws IOException {
    String output;

    workspace.runBuckCommand("build", "//java/com/preprocess:disassemble").assertSuccess();
    output = workspace.getFileContents("buck-out/gen/java/com/preprocess/content.txt");
    assertThat(output, containsString("content=2"));

    workspace.replaceFileContents(
        "java/com/preprocess/convert.py",
        "content=2",
        "content=3");

    workspace.runBuckCommand("build", "//java/com/preprocess:disassemble").assertSuccess();
    output = workspace.getFileContents("buck-out/gen/java/com/preprocess/content.txt");
    assertThat(output, containsString("content=3"));
  }

  @Test
  public void testCxxLibraryDep() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_dep").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/sample/app_cxx_lib_dep.apk"));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testCxxLibraryDepWithNoFilters() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_dep_no_filters").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/apps/sample/app_cxx_lib_dep_no_filters.apk"));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testNoCxxDepsDoesNotIncludeNdkRuntime() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_no_cxx_deps").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile("buck-out/gen/apps/sample/app_no_cxx_deps.apk"));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testProguardDontObfuscateGeneratesMappingFile() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_proguard_dontobfuscate").assertSuccess();

    Path mapping = workspace.resolve(
        "buck-out/gen/apps/sample/__app_proguard_dontobfuscate#aapt_package__proguard__/" +
            ".proguard/mapping.txt");
    assertTrue(Files.exists(mapping));
  }

}
