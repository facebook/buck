/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.junit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class DryRunIgnoreTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void shouldNotListIgnoredTestsInDryRun() throws IOException {
    assertOneTest("test", "--all", "--dry-run");
  }

  @Test
  public void shouldNotListIgnoredTestsInActualRun() throws IOException {
    assertOneTest("test", "--all");
  }

  private void assertOneTest(String... args) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "dry_run", temporaryFolder);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(args);
    result.assertSuccess();
    assertThat(
        "Of the two tests, only one shall pass, because the other one is ignored with @Ignore",
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   com.example.DryRunTest"));
  }
}
