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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryModularIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryModularIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testPrebuiltDepModular() throws IOException {
    String target = "//apps/sample:app_with_prebuilt_native_libs_modular";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/prebuilt/libs.txt");
    zipInspector.assertFileExists("assets/prebuilt/libs.xzs");
    zipInspector.assertFileExists("assets/prebuilt_asset/libs.txt");
    zipInspector.assertFileExists("assets/prebuilt_asset/libs.xzs");
  }

  @Test
  public void testCompressAssetLibsModular() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressAssetLibsModularMap() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset_modular_map";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressAssetLibsNoPackageModular() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset_no_package_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressAssetLibsNoPackageModularMap() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset_no_package_modular_map";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressLibsNoPackageModular() throws IOException {
    String target = "//apps/sample:app_cxx_lib_no_package_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressLibsNoPackageModularMap() throws IOException {
    String target = "//apps/sample:app_cxx_lib_no_package_modular_map";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testMultidexModularWithManifest() throws IOException {
    String target = "//apps/multidex:app_modular_manifest_debug";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
    zipInspector.assertFileExists("assets/" + module + "/AndroidManifest.xml");
  }

  @Test
  public void testMultidexModularWithManifestAapt2() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeAapt2WithOutputTextSymbolsIsAvailable();
    ProcessResult foundAapt2 = workspace.runBuckBuild("//apps/sample:check_for_aapt2");
    Assume.assumeTrue(foundAapt2.getExitCode().getCode() == 0);

    String target = "//apps/multidex:app_modular_manifest_aapt2_debug";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
    zipInspector.assertFileExists("assets/" + module + "/AndroidManifest.xml");
  }

  @Test
  public void testMultidexModular() throws IOException {
    String target = "//apps/multidex:app_modular_debug";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
  }

  /* Disable @Test */
  public void testMultidexProguardModular() throws IOException {
    String target = "//apps/multidex:app_modular_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "java.com.sample.small.small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
  }

  /* Disable @Test */
  public void testMultidexProguardModularWithObfuscation() throws IOException {
    String target = "//apps/multidex:app_modular_proguard_obfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "java.com.sample.small.small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
  }
}
