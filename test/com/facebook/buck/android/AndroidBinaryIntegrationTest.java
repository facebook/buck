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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.hash.Hashing;

import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AndroidBinaryIntegrationTest {

  @ClassRule
  public static TemporaryPaths projectFolderWithPrebuiltTargets = new TemporaryPaths();

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();

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

  @Before
  public void setUp() throws IOException {
    workspace = new ProjectWorkspace(
        projectFolderWithPrebuiltTargets.getRoot(), tmpFolder.getRoot());
    workspace.setUp();
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
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
        workspace.getPath(
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
        workspace.getPath(
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
        workspace.getPath(
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
        workspace.getPath(
            "buck-out/gen/apps/sample/app_cxx_lib_dep_no_filters.apk"));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testNoCxxDepsDoesNotIncludeNdkRuntime() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_no_cxx_deps").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/apps/sample/app_no_cxx_deps.apk"));
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

  @Test
  public void testStaticCxxLibraryDep() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_static_cxx_lib_dep").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_static_cxx_lib_dep.apk"));
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_bar.so");
  }

  @Test
  public void testHeaderOnlyCxxLibrary() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_header_only_cxx_lib_dep").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_header_only_cxx_lib_dep.apk"));
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_headeronly.so");
  }

  @Test
  public void testX86OnlyCxxLibrary() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_with_x86_lib").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_with_x86_lib.apk"));
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_x86-only.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi/libnative_cxx_x86-only.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_x86-only.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testApksHaveDeterministicTimestamps() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Path apk = workspace.getPath("buck-out/gen/apps/sample/app.apk");
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_EPOCH_START));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(apk))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void testCxxLibraryAsAsset() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_asset").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_cxx_lib_asset.apk"));
    zipInspector.assertFileExists("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCxxLibraryAsAssetWithoutPackaging() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_asset_no_package").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_cxx_lib_asset_no_package.apk"));
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset.so");
  }

  @Test
  public void testCompressAssetLibs() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_compress_lib_asset").assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            "buck-out/gen/apps/sample/app_compress_lib_asset.apk"));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testLibraryMetadataChecksum() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_asset").assertSuccess();
    Path pathToZip = workspace.getPath("buck-out/gen/apps/sample/app_cxx_lib_asset.apk");
    ZipFile file = new ZipFile(pathToZip.toFile());
    ZipEntry metadata = file.getEntry("assets/lib/metadata.txt");
    assertNotNull(metadata);

    BufferedReader contents = new BufferedReader(
        new InputStreamReader(file.getInputStream(metadata)));
    String line = contents.readLine();
    byte[] buffer = new byte[512];
    while (line != null) {
      // Each line is of the form <filename> <filesize> <SHA256 checksum>
      String[] tokens = line.split(" ");
      assertSame(tokens.length, 3);
      String filename = tokens[0];
      int filesize = Integer.parseInt(tokens[1]);
      String checksum = tokens[2];

      ZipEntry lib = file.getEntry("assets/lib/" + filename);
      assertNotNull(lib);
      InputStream is = file.getInputStream(lib);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      while (filesize > 0) {
        int read = is.read(buffer, 0, Math.min(buffer.length, filesize));
        assertTrue(read >= 0);
        out.write(buffer, 0, read);
        filesize -= read;
      }
      String actualChecksum = Hashing.sha256().hashBytes(out.toByteArray()).toString();
      assertEquals(checksum, actualChecksum);
      is.close();
      out.close();
      line = contents.readLine();
    }
    file.close();
    contents.close();


  }

}
