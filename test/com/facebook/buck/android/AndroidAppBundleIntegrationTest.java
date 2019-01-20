/*
 * Copyright 2018-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.bundle.Config.BundleConfig;
import com.android.bundle.Config.Bundletool;
import com.android.bundle.Config.Compression;
import com.android.bundle.Config.Optimizations;
import com.android.bundle.Config.SplitDimension;
import com.android.bundle.Config.SplitsConfig;
import com.android.bundle.Files.Assets;
import com.android.bundle.Files.NativeLibraries;
import com.android.bundle.Files.TargetedAssetsDirectory;
import com.android.bundle.Files.TargetedNativeDirectory;
import com.facebook.buck.core.exceptions.HumanReadableException;
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
import com.facebook.buck.util.zip.ZipConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidAppBundleIntegrationTest extends AbiCompilationModeTest {

  private ProjectWorkspace workspace;
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    AssumeAndroidPlatform.assumeBundleBuildIsSupported();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testAppBundleHaveDeterministicTimestamps() throws IOException {
    String target = "//apps/sample:app_bundle_1";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Path aab =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s.signed.apk"));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(aab))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }

    ZipInspector zipInspector = new ZipInspector(aab);
    zipInspector.assertFileExists("BundleConfig.pb");
    zipInspector.assertFileExists("base/dex/classes.dex");
    zipInspector.assertFileExists("base/assets.pb");
    zipInspector.assertFileExists("base/resources.pb");
    zipInspector.assertFileExists("base/manifest/AndroidManifest.xml");
    zipInspector.assertFileExists("base/assets/asset_file.txt");
    zipInspector.assertFileExists("base/res/drawable/tiny_black.png");
    zipInspector.assertFileExists("base/native.pb");
    zipInspector.assertFileExists("base/lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("base/assets/secondary-program-dex-jars/secondary-1.dex.jar");
    NativeLibraries nativeLibraries =
        NativeLibraries.parseFrom(zipInspector.getFileContents("base/native.pb"));
    assertEquals(3, nativeLibraries.getDirectoryList().size());
    for (TargetedNativeDirectory targetedNativeDirectory : nativeLibraries.getDirectoryList()) {
      assertTrue(targetedNativeDirectory.hasTargeting());
      assertTrue(targetedNativeDirectory.getTargeting().hasAbi());
    }

    Assets assets = Assets.parseFrom(zipInspector.getFileContents("base/assets.pb"));
    for (TargetedAssetsDirectory targetedAssetsDirectory : assets.getDirectoryList()) {
      assertTrue(targetedAssetsDirectory.hasTargeting());
      assertTrue(targetedAssetsDirectory.getTargeting().hasAbi());
    }

    BundleConfig bundleConfig =
        BundleConfig.parseFrom(zipInspector.getFileContents("BundleConfig.pb"));

    assertTrue(bundleConfig.hasBundletool());
    assertBundletool(bundleConfig.getBundletool());

    assertTrue(bundleConfig.hasOptimizations());
    assertOptimizations(bundleConfig.getOptimizations());

    assertTrue(bundleConfig.hasCompression());
    assertCompression(bundleConfig.getCompression());
  }

  public void assertSplitDimension(SplitDimension splitdimension, int index) {
    assertEquals(index + 1, splitdimension.getValueValue());
    assertEquals(index != 0, splitdimension.getNegate());
  }

  public void assertSplitsConfig(SplitsConfig splitsconfig) {
    assertEquals(3, splitsconfig.getSplitDimensionCount());
    for (int i = 0; i < 3; i++) {
      assertSplitDimension(splitsconfig.getSplitDimension(i), i);
    }
  }

  public void assertOptimizations(Optimizations optimizations) {
    assertTrue(optimizations.hasSplitsConfig());
    assertSplitsConfig(optimizations.getSplitsConfig());
  }

  public void assertCompression(Compression compression) {
    assertEquals(0, compression.getUncompressedGlobCount());
  }

  public void assertBundletool(Bundletool bundletool) {
    assertEquals("", bundletool.getVersion());
  }

  @Test
  public void testAppBundleHaveCorrectAaptMode() throws IOException {
    String target = "//apps/sample:app_bundle_wrong_aapt_mode";

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Android App Bundle can only be built with aapt2, but " + target + " is using aapt1.");

    workspace.runBuckBuild(target);
  }

  @Test
  public void testAppBundleWithMultipleModules() throws IOException {
    String target = "//apps/sample:app_modular_debug";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path aab =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s.signed.apk"));

    ZipInspector zipInspector = new ZipInspector(aab);
    zipInspector.assertFileExists("small_with_no_resource_deps/assets.pb");
    zipInspector.assertFileExists("small_with_no_resource_deps/native.pb");
    zipInspector.assertFileExists(
        "small_with_no_resource_deps/assets/small_with_no_resource_deps/metadata.txt");
    zipInspector.assertFileExists(
        "small_with_no_resource_deps/assets/small_with_no_resource_deps/libs.xzs");
    zipInspector.assertFileExists("base/dex/classes.dex");
    zipInspector.assertFileExists("base/dex/classes2.dex");
    zipInspector.assertFileExists("small_with_no_resource_deps/manifest/AndroidManifest.xml");
    zipInspector.assertFileExists("small_with_no_resource_deps/resources.pb");
  }
}
