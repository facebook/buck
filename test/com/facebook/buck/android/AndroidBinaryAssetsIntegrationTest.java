/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryAssetsIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryAssetsIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testAppHasAssets() throws IOException {
    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);

    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/hilarity.txt");
    zipInspector.assertFileContents(
        "assets/hilarity.txt",
        workspace.getFileContents("res/com/sample/base/buck-assets/hilarity.txt"));

    // Test that after changing an asset, the new asset is in the apk.
    String newContents = "some new contents";
    workspace.writeContentsToPath(newContents, "res/com/sample/base/buck-assets/hilarity.txt");
    workspace.buildAndReturnOutput(SIMPLE_TARGET);
    zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileContents("assets/hilarity.txt", newContents);
  }

  @Test
  public void testAppAssetsAreCompressed() throws IOException {
    // Small files don't get compressed. Make something a bit bigger.
    String largeContents =
        Joiner.on("\n").join(Collections.nCopies(100, "A boring line of content."));
    workspace.writeContentsToPath(largeContents, "res/com/sample/base/buck-assets/hilarity.txt");

    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);
    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/hilarity.txt");
    zipInspector.assertFileContents(
        "assets/hilarity.txt",
        workspace.getFileContents("res/com/sample/base/buck-assets/hilarity.txt"));
    zipInspector.assertFileIsCompressed("assets/hilarity.txt");
  }

  @Test
  public void testAppUncompressableAssetsAreNotCompressed() throws IOException {
    // Small files don't get compressed. Make something a bit bigger.
    String largeContents =
        Joiner.on("\n").join(Collections.nCopies(100, "A boring line of content."));
    workspace.writeContentsToPath(largeContents, "res/com/sample/base/buck-assets/movie.mp4");

    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);
    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/movie.mp4");
    zipInspector.assertFileContents(
        "assets/movie.mp4", workspace.getFileContents("res/com/sample/base/buck-assets/movie.mp4"));
    zipInspector.assertFileIsNotCompressed("assets/movie.mp4");
  }

  @Test
  public void testGzAssetsAreRejected() throws IOException {
    workspace.writeContentsToPath("some contents", "res/com/sample/base/buck-assets/zipped.gz");

    ProcessResult result = workspace.runBuckBuild(SIMPLE_TARGET);
    result.assertFailure();
    assertTrue(result.getStderr().contains("zipped.gz"));
  }

  @Test
  public void testCompressAssetLibs() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }
}
