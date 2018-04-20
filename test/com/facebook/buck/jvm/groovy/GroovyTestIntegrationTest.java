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

package com.facebook.buck.jvm.groovy;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GroovyTestIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(System.getenv("GROOVY_HOME") != null);

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "groovy_test_description", tmp, true);
    workspace.setUp();
  }

  @Test
  public void allTestsPassingMakesTheBuildResultASuccess() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("test", "//com/example/spock:passing");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void oneTestFailingMakesTheBuildResultAFailure() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("test", "//com/example/spock:failing");
    buildResult.assertTestFailure();
  }

  @Test
  public void compilationFailureMakesTheBuildResultAFailure() throws Exception {
    ProcessResult buildResult =
        workspace.runBuckCommand("test", "//com/example/spock:will_not_compile");
    buildResult.assertFailure();
  }
}
