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

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolGetter;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolsAndDtNeeded;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class NdkLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp1 = new TemporaryPaths();

  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();

  @Before
  public void setUp() {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
  }

  @Test
  public void cxxLibraryDep() throws IOException {
    ProjectWorkspace workspace1 =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(this, "cxx_deps", tmp1);
    workspace1.setUp();
    workspace1.runBuckBuild("//jni:foo").assertSuccess();

    ProjectWorkspace workspace2 =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(this, "cxx_deps", tmp2);
    workspace2.setUp();
    workspace2.runBuckBuild("//jni:foo").assertSuccess();

    // Verify that rule keys generated from building in two different working directories
    // does not affect the rule key.
    assertNotEquals(workspace1.resolve(Paths.get("test")), workspace2.resolve(Paths.get("test")));
    assertEquals(
        workspace1.getBuildLog().getRuleKey("//jni:foo"),
        workspace2.getBuildLog().getRuleKey("//jni:foo"));
  }

  @Test
  public void sourceFilesChangeTargetHash() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp1);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "//jni:foo", "--show-target-hash", "--target-hash-file-mode=PATHS_ONLY");
    result.assertSuccess();
    String[] targetAndHash = result.getStdout().trim().split("\\s+");
    assertEquals("//jni:foo", targetAndHash[0]);
    String hashBefore = targetAndHash[1];

    ProcessResult result2 =
        workspace.runBuckCommand(
            "targets",
            "//jni:foo",
            "--show-target-hash",
            "--target-hash-file-mode=PATHS_ONLY",
            "--target-hash-modified-paths=" + workspace.resolve("jni/foo.cpp"));

    result2.assertSuccess();
    String[] targetAndHash2 = result2.getStdout().trim().split("\\s+");
    assertEquals("//jni:foo", targetAndHash2[0]);
    String hashAfter = targetAndHash2[1];

    assertNotEquals(hashBefore, hashAfter);
  }

  @Test
  public void ndkLibraryOwnsItsSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_deps", tmp1);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "query", String.format("owner(%s)", workspace.resolve("jni/foo.cpp")));
    result.assertSuccess();
    assertEquals("//jni:foo", result.getStdout().trim());
  }

  @Test
  public void ndkLibraryAppPlatformDefaultCpuAbi() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp1);
    workspace.setUp();
    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = armv7, x86");
    workspace.replaceFileContents(
        ".buckconfig",
        "#app_platform",
        "app_platform = android-16\n  app_platform_per_cpu_abi = armv7 => android-19");
    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_cxx_lib_app_platform");

    SymbolsAndDtNeeded info;
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    SymbolGetter syms = getSymbolGetter(filesystem, tmp1);

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("lib/x86/libnative_app_platform_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_app_platform_lib.so");

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_app_platform_lib.so");
    assertThat(info.symbols.global, Matchers.hasItem("Android16"));
    assertThat(info.symbols.global, not(Matchers.hasItem("Android19")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/armeabi-v7a/libnative_app_platform_lib.so");
    assertThat(info.symbols.global, Matchers.hasItem("Android19"));
    assertThat(info.symbols.global, not(Matchers.hasItem("Android16")));
  }

  @Test
  public void ndkLibraryAppPlatformByCpuAbi() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp1);
    workspace.setUp();
    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = armv7, x86");
    workspace.replaceFileContents(
        ".buckconfig",
        "#app_platform",
        "app_platform_per_cpu_abi = x86 => android-18, armv7 => android-19");
    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_cxx_lib_app_platform");

    SymbolsAndDtNeeded info;
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    SymbolGetter syms = getSymbolGetter(filesystem, tmp1);

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("lib/x86/libnative_app_platform_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_app_platform_lib.so");

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_app_platform_lib.so");
    assertThat(info.symbols.global, Matchers.hasItem("Android18"));
    assertThat(info.symbols.global, not(Matchers.hasItem("Android19")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/armeabi-v7a/libnative_app_platform_lib.so");
    assertThat(info.symbols.global, Matchers.hasItem("Android19"));
    assertThat(info.symbols.global, not(Matchers.hasItem("Android18")));
  }

  private SymbolGetter getSymbolGetter(ProjectFilesystem filesystem, TemporaryPaths tempLocation)
      throws IOException {
    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(filesystem);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path tmpDir = tempLocation.newFolder("symbols_tmp");
    return new SymbolGetter(
        new DefaultProcessExecutor(new TestConsole()), tmpDir, platform.getObjdump(), pathResolver);
  }
}
