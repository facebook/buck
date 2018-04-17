/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cxx.endtoend;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.EnvironmentFor;
import com.facebook.buck.testutil.endtoend.ToggleState;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class CxxEndToEndTest {

  private static final String successTarget =
      "//simple_successful_helloworld:simple_successful_helloworld";
  private static final String failTarget = "//simple_failed_helloworld:simple_failed_helloworld";

  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment()
        .withBuckdToggled(ToggleState.ON_OFF)
        .addTemplates("cxx")
        .withCommand("build");
  }

  @Environment
  public static EndToEndEnvironment setSuccessEnvironment() {
    return getBaseEnvironment().withTargets(successTarget);
  }

  @EnvironmentFor(testNames = {"shouldNotBuildSuccessfully"})
  public static EndToEndEnvironment setFailEnvironment() {
    return getBaseEnvironment().withTargets(failTarget);
  }

  @Test
  public void shouldBuildAndRunSuccessfully(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertSuccess(String.format("%s did not successfully build", test.getName()));
    ProcessResult targetResult = workspace.runBuiltResult(successTarget);
    targetResult.assertSuccess();
  }

  @Test
  public void shouldFailInSuccessfulEnv(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    // Uses successful environment, but fixture BUCK file is empty
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure(
        String.format("%s successfully built when it has an empty BUCK file", test.getName()));
  }

  @Test
  public void shouldNotBuildSuccessfully(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure(
        String.format(
            "%s successfully built when it should have failed to compile", test.getName()));
  }
}
