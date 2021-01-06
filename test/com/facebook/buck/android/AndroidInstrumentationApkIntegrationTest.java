/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class AndroidInstrumentationApkIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Test
  public void testCxxLibraryDep() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    String target = "//:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    if (AssumeAndroidPlatform.get(workspace).isArmAvailable()) {
      zipInspector.assertFileExists("lib/armeabi/libcxx.so");
      zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    }
    zipInspector.assertFileExists("lib/armeabi-v7a/libcxx.so");
    zipInspector.assertFileExists("lib/x86/libcxx.so");
    if (AssumeAndroidPlatform.get(workspace).isGnuStlAvailable()) {
      zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
      zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
    } else {
      zipInspector.assertFileExists("lib/armeabi-v7a/libc++_shared.so");
      zipInspector.assertFileExists("lib/x86/libc++_shared.so");
    }
  }

  @Test
  public void instrumentationApkCannotTestAnotherInstrumentationApk() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_instrumentation_apk_integration_test", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    setWorkspaceCompilationMode(workspace);

    ProcessResult result =
        workspace.runBuckCommand("build", "//:instrumentation_apk_with_instrumentation_apk");
    assertThat(
        result.getStderr(),
        containsString(
            "In //:instrumentation_apk_with_instrumentation_apk, apk='//:app_cxx_lib_dep'"
                + " must be an android_binary() or apk_genrule() but was"
                + " android_instrumentation_apk()."));
  }
}
