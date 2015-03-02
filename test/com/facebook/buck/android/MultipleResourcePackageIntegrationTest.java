/*
 *
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.

 */

package com.facebook.buck.android;

import static org.junit.Assert.assertFalse;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class MultipleResourcePackageIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);

    workspace.setUp();
  }

  @Test
  public void testRDotJavaFilesPerPackage() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//apps/sample:app_with_multiple_rdot_java_packages").assertSuccess();

    Path uberRDotJavaDir = AaptPackageResources.getPathToGeneratedRDotJavaSrcFiles(
        BuildTargetFactory.newInstance(
            "//apps/sample:app_with_multiple_rdot_java_packages#aapt_package"));

    String sampleRJava = workspace.getFileContents(
        uberRDotJavaDir.resolve("com/sample/R.java").toString());
    String sample2RJava = workspace.getFileContents(
        uberRDotJavaDir.resolve("com/sample2/R.java").toString());

    assertFalse(sampleRJava.contains("sample2_string"));

    assertFalse(sample2RJava.contains("app_icon"));
    assertFalse(sample2RJava.contains("tiny_black"));
    assertFalse(sample2RJava.contains("tiny_something"));
    assertFalse(sample2RJava.contains("tiny_white"));
    assertFalse(sample2RJava.contains("top_layout"));
    assertFalse(sample2RJava.contains("app_name"));
    assertFalse(sample2RJava.contains("base_button"));
  }
}
