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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidInstrumentationApkIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  @Test
  public void testCxxLibraryDep() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        new AndroidBinaryIntegrationTest(),
        "android_instrumentation_apk_integration_test",
        tmpFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:app_cxx_lib_dep").assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getFile(
            "buck-out/gen/app_cxx_lib_dep.apk"));
    zipInspector.assertFileExists("lib/armeabi/libcxx.so");
    zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libcxx.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libcxx.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
  }

}
