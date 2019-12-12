/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Paths;
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
    workspace.addTemplateToWorkspace(Paths.get("test/com/facebook/buck/toolchains/kotlin"));
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
  }

  /** Tests that a Test Rule without any tests to run does not fail. */
  @Test
  public void emptyTestRule() {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/empty_test:test");
    result.assertSuccess("An empty test rule should pass.");
  }

  @Test
  public void allTestsPassingMakesTheBuildResultASuccess() {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:passing");
    result.assertSuccess("Build should've succeeded.");
  }

  @Test
  public void oneTestFailingMakesBuildResultAFailure() {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:failing");
    result.assertTestFailure();
  }

  @Test
  public void compilationFailureMakesTheBuildResultAFailure() {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/basic:failing");
    result.assertTestFailure("Test should've failed.");
  }

  @Test
  public void weCanAccessAnotherModuleInternalModuleByAddingItToFriendPaths() {
    ProcessResult result = workspace.runBuckCommand("test", "//com/example/friend_paths:passing");
    result.assertSuccess("Build should've succeeded.");
  }

  @Test
  public void weCanAccessAnotherModuleInternalModuleByAddingItToFriendPathsWithClassAbis() {
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "//com/example/friend_paths:passing", "-c", "kotlin.compile_against_abis=true");
    result.assertSuccess("Build should've succeeded.");
  }
}
