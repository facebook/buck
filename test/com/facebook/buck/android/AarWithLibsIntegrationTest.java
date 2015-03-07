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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AarWithLibsIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testLibsInAarAreIncludedInApk() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmp);
    workspace.setUp();

    // More importantly, the content of the libs/ should end up in the .apk of an android_binary()
    // that transitively includes //:aar, but this is easier to test.
    workspace.runBuckCommand("build", "//java/com/example/aar:extract-classes-dex").assertSuccess();

    String smali = workspace.getFileContents("buck-out/gen/java/com/example/aar/smali-files.txt");

    String primaryClass = MorePaths.pathWithPlatformSeparators("com/example/PrimaryClass.smali");
    String dependency = MorePaths.pathWithPlatformSeparators(
        "com/example/dependency/Dependency.smali");

    assertThat(
        "A class from the classes.jar in the .aar should make it into the APK.",
        smali,
        containsString(primaryClass));
    assertThat(
        "A class from a jar in the libs directory in the .aar should make it into the APK.",
        smali,
        containsString(dependency));
  }
}
