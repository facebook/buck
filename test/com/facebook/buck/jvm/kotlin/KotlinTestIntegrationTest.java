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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class KotlinTestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public Timeout timeout = Timeout.seconds(180);

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "kotlin_test_description", tmp);
    workspace.setUp();

    Path kotlincPath = TestDataHelper.getTestDataScenario(this, "kotlinc");
    MostFiles.copyRecursively(kotlincPath, tmp.newFolder("kotlinc"));

    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
  }

  /** Tests that a Test Rule without any tests to run does not fail. */
  @Test
  public void emptyTestRule() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/empty_test:test");
    result.assertSuccess("An empty test rule should pass.");
  }

  @Test
  public void allTestsPassingMakesTheBuildResultASuccess() throws Exception {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:passing");
    result.assertSuccess("Build should've succeeded.");
  }

  @Test
  public void oneTestFailingMakesBuildResultAFailure() throws Exception {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:failing");
    result.assertTestFailure();
  }

  @Test
  public void compilationFailureMakesTheBuildResultAFailure() throws Exception {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:failing");
    result.assertTestFailure("Test should've failed.");
  }

  @Test
  public void weCanAccessAnotherModuleInternalModuleByAddingItToFriendPaths() throws Exception {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/friend_paths:passing");
    result.assertSuccess("Build should've succeeded.");
  }
}
