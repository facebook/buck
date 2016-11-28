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

package com.facebook.buck.testrunner;

import static org.hamcrest.MatcherAssert.assertThat;

import static com.facebook.buck.testutil.OutputHelper.containsBuckTestOutputLine;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class DryRunIgnoreTestNGTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUpWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "dry_run_testng", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void shouldNotListIgnoredTestsInDryRun() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--all", "--dry-run");
    result.assertSuccess();
    assertThat(
        "One test will be run, and one will be skipped.",
        result.getStderr(),
        containsBuckTestOutputLine(
            "DRYRUN", 1, 1, 0, "com.example.DryRunTestNGTest"));
  }

  @Test
  public void shouldNotListIgnoredTestsInActualRun() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--all");
    result.assertSuccess();
    assertThat(
        "One test should run/pass, the other one is not enabled",
        result.getStderr(),
        containsBuckTestOutputLine(
            "PASS", 1, 1, 0, "com.example.DryRunTestNGTest"));
  }

}
