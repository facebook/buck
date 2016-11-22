/*
 * Copyright 2016-present Facebook, Inc.
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


import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests for AndroidLibraryDescription
 */
public class AndroidLibraryDescriptionIntegrationTest {
  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_library_dynamic_deps", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testQueryDepsNotInvalidatedWhenRuleKeyHit() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:android_resources").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_resources");

    // Now, build again, assert we get the previous result
    workspace.runBuckCommand("build", "//:android_resources").assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:android_resources");

    // Now, edit lib_b, which is NOT part of the query result,
    workspace.replaceFileContents("B.java", "// method", "public static void foo() {}");
    workspace.runBuckCommand("build", "//:android_resources").assertSuccess();

    // And assert that we don't get rebuilt, BUT we had to re-run the query to find out
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//:android_resources");
  }

  @Test
  public void testDepQueryResultsAreInvalidatedWhenDirectDepChanges() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");

    // Now, edit lib_a, which is NOT part of the query result, and assert the query does not run
    workspace.replaceFileContents("A.java", "// method", "public static void foo() {}");
    workspace.runBuckCommand("build", "//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:android_libraries");

    // Now, add lib_a to the 'top_level' library and build again
    workspace.replaceFileContents("BUCK", "#placeholder", "':lib_a',");
    workspace.runBuckCommand("build", "//:android_libraries");

    // Now we should rebuild top_level, re-run the query, and rebuild android_libraries
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");
  }

  @Test
  public void testDepQueryResultsAreInvalidatedWhenTransitiveDepChanges() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");

    // Now, edit lib_b, which is part of the query result, and assert the query is invalidated
    workspace.replaceFileContents("B.java", "// method", "public static void foo() {}");
    workspace.runBuckCommand("build", "//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib_b");
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");
  }

  @Test
  public void testDepQueryResultsAreUpdatedWhenAttributesChange() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckCommand("build", "//:has_proc_params").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:has_proc_params");

    // Now, add annotation proc params to lib_b and assert the query is updated
    workspace.replaceFileContents("BUCK", "#annotation_placeholder", "'example.foo=True',");
    workspace.runBuckCommand("build", "//:has_proc_params").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:has_proc_params");
  }
}
