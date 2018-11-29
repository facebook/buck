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
package com.facebook.buck.cli;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConfigurationWarningsIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  /** Returns a Pattern that's suitable for using built in hamcrest matchers on */
  private Pattern makeStdErrPattern(String pattern) {
    return Pattern.compile(".*^" + pattern + ".*$.*", Pattern.MULTILINE | Pattern.DOTALL);
  }

  @Test
  public void errorMesagesShouldBePrinted() throws Throwable {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "configuration_warnings", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("ui", "warn_on_config_file_overrides", "false");

    ProcessResult result =
        workspace.runBuckCommand(ImmutableMap.of("HOME", tmp.getRoot().toString()), "query", "//:");

    result.assertSuccess();
    assertThat(
        result.getStderr(), not(containsString("Using additional configuration options from")));
  }

  @Test
  public void filesInIgnoreListAreIgnored() throws Throwable {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "configuration_warnings", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption(
        "ui", "warn_on_config_file_overrides_ignored_files", "test_config");

    ProcessResult result =
        workspace.runBuckCommand(
            ImmutableMap.of("HOME", tmp.getRoot().toString()), "query", "//...");

    result.assertSuccess();
    assertThat(
        result.getStderr(),
        matchesPattern(
            makeStdErrPattern(
                "Using additional configuration options from.* \\.buckconfig\\.local")));
    assertThat(
        result.getStderr(),
        not(
            matchesPattern(
                makeStdErrPattern(
                    "Using additional configuration options from.* \\.buckconfig\\.d[/\\\\]test_config"))));
    assertThat(
        result.getStderr(),
        not(
            matchesPattern(
                makeStdErrPattern(
                    "Using additional configuration options from.* \\.buckconfig[^\\.]"))));
  }

  @Test
  public void absentIgnoreListShowsAllFiles() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "configuration_warnings", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("foo", "bar", "baz1");

    ProcessResult result = workspace.runBuckCommand("query", "//...");

    result.assertSuccess();
    assertThat(
        result.getStderr(),
        matchesPattern(
            makeStdErrPattern(
                "Using additional configuration options from.* \\.buckconfig\\.local")));
    assertThat(
        result.getStderr(),
        matchesPattern(
            makeStdErrPattern(
                "Using additional configuration options from.* \\.buckconfig\\.d[/\\\\]test_config")));
    assertThat(
        result.getStderr(),
        not(
            matchesPattern(
                makeStdErrPattern(
                    "Using additional configuration options from.* \\.buckconfig[^\\.]"))));
  }
}
