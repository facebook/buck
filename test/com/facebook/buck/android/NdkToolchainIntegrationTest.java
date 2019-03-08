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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxToolchainHelper;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class NdkToolchainIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
  }

  @Test
  public void testBuildWithCustomNdkToolchain() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "ndk_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);

    // workspace.addBuckConfigLocalOption("cxx#good", "toolchain_target", "//toolchain:good");
    workspace.addBuckConfigLocalOption(
        "ndk",
        "toolchain_target_per_cpu_abi",
        "armv7 => //ndk_toolchain:good, arm64 => //ndk_toolchain:good, x86 => //ndk_toolchain:bad");
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("-c", "ndk.cpu_abis=armv7,arm64", "//:fat_apk");

    ZipInspector inspector = new ZipInspector(output);
    inspector.assertFileContents("lib/armeabi-v7a/libc++_shared.so", "strip:\nit's a runtime");
    inspector.assertFileContents("lib/arm64-v8a/libc++_shared.so", "strip:\nit's a runtime");
    inspector.assertFileContents(
        "lib/armeabi-v7a/libnative.so", "strip:\nlinker:\ncompile output: native");
    inspector.assertFileContents(
        "lib/arm64-v8a/libnative.so", "strip:\nlinker:\ncompile output: native");
  }

  @Test
  public void testBuildWithBadToolchain() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "ndk_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);

    workspace.addBuckConfigLocalOption(
        "ndk",
        "toolchain_target_per_cpu_abi",
        "armv7 => //ndk_toolchain:good, arm64 => //ndk_toolchain:good, x86 => //ndk_toolchain:bad");
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("-c", "ndk.cpu_abis=x86", "//:fat_apk");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("stderr: unimplemented"));
  }

  // TODO(cjhopman): Consider adding a test that the relinker uses the custom objdump.
}
