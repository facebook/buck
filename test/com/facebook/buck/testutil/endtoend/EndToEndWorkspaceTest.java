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

package com.facebook.buck.testutil.endtoend;

import com.facebook.buck.testutil.PlatformUtils;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.util.ExitCode;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class EndToEndWorkspaceTest {
  @Rule public EndToEndWorkspace workspace = new EndToEndWorkspace();

  private PlatformUtils platformUtils = PlatformUtils.getForPlatform();

  @Before
  public void buildCppEnv() throws Exception {
    workspace.addPremadeTemplate("cxx");
  }

  @Test
  public void shouldBuildSuccess() throws Exception {
    String target = "//simple_successful_helloworld:simple_successful_helloworld";
    Optional<String> flavor = platformUtils.getFlavor();
    if (flavor.isPresent()) {
      target += "#" + flavor.get();
    }
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertExitCode(
        "simple_successful_helloworld did not successfully build", ExitCode.map(0));
  }

  @Test
  public void shouldBuildFail() throws Exception {
    String target = "//simple_failed_helloworld:simple_failed_helloworld";
    Optional<String> flavor = platformUtils.getFlavor();
    if (flavor.isPresent()) {
      target += "#" + flavor.get();
    }
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertFailure(
        "simple_failed_helloworld successfully built when it should have failed to compile");
  }

  @Test
  public void shouldBuildSuccessWithBuckd() throws Exception {
    String target = "//simple_successful_helloworld:simple_successful_helloworld";
    Optional<String> flavor = platformUtils.getFlavor();
    if (flavor.isPresent()) {
      target += "#" + flavor.get();
    }
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertExitCode(
        "simple_successful_helloworld did not successfully build", ExitCode.map(0));
  }

  @Test
  public void shouldBuildFailWithBuckd() throws Exception {
    String target = "//simple_failed_helloworld:simple_failed_helloworld";
    Optional<String> flavor = platformUtils.getFlavor();
    if (flavor.isPresent()) {
      target += "#" + flavor.get();
    }
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertFailure("simple_failed_helloworld built when it should have failed to compile");
  }
}
