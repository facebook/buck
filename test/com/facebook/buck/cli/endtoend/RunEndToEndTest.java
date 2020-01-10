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

package com.facebook.buck.cli.endtoend;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.ToggleState;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class RunEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment()
        .withCommand("run")
        .withBuckdToggled(ToggleState.ON)
        .addTemplates("cli");
  }

  @Test
  public void shouldRunBuiltBinaries(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    ProcessResult result =
        workspace.runBuckCommand(
            test.getBuckdEnabled(),
            ImmutableMap.copyOf(test.getVariableMap()),
            test.getTemplateSet(),
            "run",
            "-c",
            "user.exit_code=0",
            "//run/simple_bin:main");
    result.assertSuccess();

    result =
        workspace.runBuckCommand(
            test.getBuckdEnabled(),
            ImmutableMap.copyOf(test.getVariableMap()),
            test.getTemplateSet(),
            "run",
            "-c",
            "user.exit_code=" + Integer.toString(ExitCode.TEST_NOTHING.getCode()),
            "//run/simple_bin:main");
    result.assertExitCode(ExitCode.TEST_NOTHING);
  }

  @Test
  public void shouldUseBuildErrorCodeOnBuildFailure(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Throwable {
    ProcessResult result =
        workspace.runBuckCommand(
            test.getBuckdEnabled(),
            ImmutableMap.copyOf(test.getVariableMap()),
            test.getTemplateSet(),
            "run",
            "//run/simple_bin:broken");
    result.assertExitCode(ExitCode.BUILD_ERROR);
  }
}
