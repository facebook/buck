/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class ShTestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void args() throws IOException {
    Assume.assumeTrue(ImmutableSet.of(Platform.MACOS, Platform.LINUX).contains(Platform.detect()));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sh_test_args", tmp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//foo:test").assertSuccess();
  }

  @Test
  public void timeout() throws IOException {
    Assume.assumeTrue(ImmutableSet.of(Platform.MACOS, Platform.LINUX).contains(Platform.detect()));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sh_test_timeout", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:test-spin");
    String stderr = result.getStderr();
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    assertTrue(stderr, stderr.contains("Timed out running test: //:test-spin"));
  }

  @Test
  public void env() throws IOException {
    Assume.assumeTrue(ImmutableSet.of(Platform.MACOS, Platform.LINUX).contains(Platform.detect()));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sh_test_env", tmp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//foo:test").assertSuccess();
  }

  @Test
  public void type() throws IOException {
    Assume.assumeTrue(ImmutableSet.of(Platform.MACOS, Platform.LINUX).contains(Platform.detect()));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sh_test_type", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand(
            "test",
            "-c",
            "test.external_runner=" + workspace.getPath("external_runner.sh"),
            "//:test")
        .assertSuccess();
  }
}
