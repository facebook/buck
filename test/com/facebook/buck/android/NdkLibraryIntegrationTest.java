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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class NdkLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp1 = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmp2 = new DebuggableTemporaryFolder();

  @Test
  public void cxxLibraryDep() throws IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    ProjectWorkspace workspace1 = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_deps", tmp1);
    workspace1.setUp();
    workspace1.runBuckBuild("//jni:foo").assertSuccess();

    ProjectWorkspace workspace2 = TestDataHelper.createProjectWorkspaceForScenario(
        this, "cxx_deps", tmp2);
    workspace2.setUp();
    workspace2.runBuckBuild("//jni:foo").assertSuccess();

    // Verify that rule keys generated from building in two different working directories
    // does not affect the rule key.
    BuildTarget target = BuildTargetFactory.newInstance("//jni:foo");
    assertNotEquals(workspace1.resolve(Paths.get("test")), workspace2.resolve(Paths.get("test")));
    assertEquals(
        workspace1.getFileContents(
            NdkLibraryDescription.getGeneratedMakefilePath(target).toString()),
        workspace2.getFileContents(
            NdkLibraryDescription.getGeneratedMakefilePath(target).toString()));
    assertEquals(
        workspace1.getBuildLog().getRuleKey("//jni:foo"),
        workspace2.getBuildLog().getRuleKey("//jni:foo"));
  }

}
