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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AndroidResourceIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_resource", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testOnlyUsesFirstOrderResources() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//res1:res");
    result.assertFailure();
    assertTrue(result.getStderr().contains("The following resources were not found"));
    assertTrue(result.getStderr().contains("another_name"));
    workspace.replaceFileContents("res1/BUCK", "#EXTRA_DEP_HERE", "'//res3:res',");
    workspace.runBuckBuild("//res1:res").assertSuccess();
  }
}
