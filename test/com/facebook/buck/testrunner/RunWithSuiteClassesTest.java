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

package com.facebook.buck.testrunner;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Splitter;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

public class RunWithSuiteClassesTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void normal() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this,
            "run_with_suite_classes",
            temporaryFolder);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "//:test").assertSuccess();
    List<String> stderrLines = Splitter.on('\n').splitToList(result.getStderr());
    assertThat(
        stderrLines,
        Matchers.hasItem(
            Matchers.matchesPattern(
                Pattern.compile("^PASS .* 1 Passed   0 Skipped   0 Failed   A$"))));
    assertThat(
        stderrLines,
        Matchers.hasItem(
            Matchers.matchesPattern(
                Pattern.compile("^PASS .* 1 Passed   0 Skipped   0 Failed   B$"))));
  }

  @Test
  public void dryRun() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this,
            "run_with_suite_classes",
            temporaryFolder);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("test", "--dry-run", "//:test").assertSuccess();
    List<String> stderrLines = Splitter.on('\n').splitToList(result.getStderr());
    assertThat(
        stderrLines,
        Matchers.hasItem(
            Matchers.matchesPattern(
                Pattern.compile("^DRYRUN .* 1 Passed   0 Skipped   0 Failed   A$"))));
    assertThat(
        stderrLines,
        Matchers.hasItem(
            Matchers.matchesPattern(
                Pattern.compile("^DRYRUN .* 1 Passed   0 Skipped   0 Failed   B$"))));
  }

}
