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

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.string.MoreStrings;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidLibraryGraphEnhancerIntegrationTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_library_graph_enhancer", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testPullsResourcesFromDeps() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result = workspace.runBuckBuild("//:lib_with_direct_dep");
    result.assertSuccess();
  }

  @Test
  public void testPullsResourcesFromProvidedDeps() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result = workspace.runBuckBuild("//:lib_with_provided_dep");
    result.assertSuccess();
  }

  @Test
  public void testDoesNotPullResourcesFromJavaResources() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result = workspace.runBuckBuild("//:lib_with_java_resources_dep");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            MoreStrings.linesToText(
                "Test.java:1: error: package com.facebook.buck does not exist",
                "import com.facebook.buck.R;",
                "                        ^")));
  }

  @Test
  public void testDoesNotPullResourcesFromLicenses() throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result = workspace.runBuckBuild("//:lib_with_licenses_dep");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            MoreStrings.linesToText(
                "Test.java:1: error: package com.facebook.buck does not exist",
                "import com.facebook.buck.R;",
                "                        ^")));
  }
}
