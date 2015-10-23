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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class CxxTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void spinningTestTimesOut() throws IOException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "slow_cxx_tests",
        temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        Joiner.on('\n').join(
            "[test]",
            "rule_timeout = 250",
            "[cxx]",
            "gtest_dep = //:fake-gtest"),
        ".buckconfig");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", 42);
    String stderr = result.getStderr();
    assertThat(stderr, Matchers.containsString("Timed out running test command"));
  }
}
