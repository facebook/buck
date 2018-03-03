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

package com.facebook.buck.cxx;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.ToggleState;
import com.facebook.buck.util.ExitCode;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class CxxDependentOnPyEndToEndTest {
  @Environment
  public static EndToEndEnvironment setEnvironment() {
    return new EndToEndEnvironment()
        .withBuckdToggled(ToggleState.ON_OFF)
        .addTemplates("cxx_dependent_on_py")
        .withCommand("build")
        .withTargets("//main_bin:main_bin");
  }

  @Test
  public void shouldBuildSuccessfully(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace, ProcessResult result) {
    result.assertExitCode(
        String.format("%s did not successfully build", test.getName()), ExitCode.map(0));
  }
}
