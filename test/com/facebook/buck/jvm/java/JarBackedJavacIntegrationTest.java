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

package com.facebook.buck.jvm.java;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JarBackedJavacIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "jar_backed_javac", tmp);
    workspace.setUp();
    workspace.enableDirCache();
  }

  @Test
  public void testJarBackedJavacFromJavaLibrary() throws IOException {
    ProcessResult buildResult = workspace.runBuckBuild("//:lib");
    buildResult.assertSuccess();
  }

  @Test
  public void testJarBackedJavacFromJavaLibraryCachedProperly() throws IOException {
    runTestForCacheableJavaLibraryBuild(new String[] {"//:lib"});
  }

  private void runTestForCacheableJavaLibraryBuild(String[] args) throws IOException {
    ProcessResult buildResult = workspace.runBuckBuild(args);
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildResult.assertSuccess();
    buildLog.assertTargetBuiltLocally("//:lib");
    buildLog.assertTargetBuiltLocally("//:javac_jar");
    buildLog.assertTargetBuiltLocally("//:javac_jar_impl");

    buildResult = workspace.runBuckCommand("clean", "--keep-cache");
    buildResult.assertSuccess();

    workspace.replaceFileContents("Test.java", "foo", "bar");

    buildResult = workspace.runBuckBuild(args);
    buildLog = workspace.getBuildLog();
    buildResult.assertSuccess();
    buildLog.assertTargetBuiltLocally("//:lib");
    buildLog.assertTargetWasFetchedFromCache("//:javac_jar");
    buildLog.assertTargetWasFetchedFromCache("//:javac_jar_impl");
  }
}
