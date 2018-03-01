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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.WindowsUtils;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests that errors and warnings issued by a compiler/linker are visible in buck logs and console.
 * The subtlety is that on windows cl.exe ouputs errors to stdout, not to stderr.
 */
public class CxxErrorsIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private WindowsUtils windowsUtils = new WindowsUtils();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "errors", tmp);
    workspace.setUp();
    windowsUtils.setUpWorkspace(workspace);

    if (Platform.detect() == Platform.WINDOWS) {
      windowsUtils.checkAssumptions();
    }
  }

  @Test
  public void compilerError() throws IOException {
    ProcessResult runResult =
        workspace.runBuckCommand(
            "build", "//:not_compilable#static," + CxxPlatforms.getHostFlavor().getName());
    runResult.assertFailure();
    assertThat(runResult.getStderr(), Matchers.containsString("foo.h"));
  }

  @Test
  public void linkError() throws IOException {
    ProcessResult staticBuildResult =
        workspace.runBuckCommand(
            "build", "//:not_linkable#static," + CxxPlatforms.getHostFlavor().getName());
    staticBuildResult.assertSuccess();

    ProcessResult sharedBuildResult =
        workspace.runBuckCommand(
            "build", "//:not_linkable#shared," + CxxPlatforms.getHostFlavor().getName());
    sharedBuildResult.assertFailure();
    assertThat(
        sharedBuildResult.getStderr(), Matchers.containsString("unresolvedExternalFunction"));
  }
}
