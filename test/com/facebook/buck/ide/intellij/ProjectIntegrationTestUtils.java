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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Lists;

import java.io.IOException;

public class ProjectIntegrationTestUtils {

  public static ProjectWorkspace.ProcessResult runBuckProject(
      Object testCase,
      TemporaryPaths temporaryFolder,
      String folderWithTestData,
      boolean verifyWorkspace,
      String... commandArgs) throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        testCase, folderWithTestData, temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        Lists.asList("project", commandArgs).toArray(new String[commandArgs.length + 1]));
    result.assertSuccess("buck project should exit cleanly");

    if (verifyWorkspace) {
      workspace.verify();
    }

    return result;
  }

  private ProjectIntegrationTestUtils() {
  }
}
