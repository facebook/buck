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
import java.io.IOException;
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

  @Test
  public void printsErrorMessageWhenConfigChanges() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "configuration_warnings", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckdCommand("query", "//...").assertSuccess();

    assertThat(
        result.getStderr(),
        not(
            containsString(
                "Invalidating internal cached state: Buck configuration options changed between invocations. This may cause slower builds.")));

    workspace.addBuckConfigLocalOption("foo", "bar", "baz1");
    result = workspace.runBuckdCommand("query", "-c", "foo.bar=baz1", "//...").assertSuccess();
    assertThat(
        result.getStderr(),
        containsString(
            "Invalidating internal cached state: Buck configuration options changed between invocations. This may cause slower builds."));
  }

  @Test
  public void printsIniFileOnInvalidConfigFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "configuration_warnings", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[foo]\nbar = baz\nno_value", ".buckconfig");

    String expected =
        String.format(
            "parse error (in %s at line 3): no_value",
            tmp.getRoot().resolve(".buckconfig").toString());

    ProcessResult result = workspace.runBuckdCommand("query", "//...").assertFailure();

    assertThat(result.getStderr(), containsString(expected));
  }

  @Test
  public void printsErrorsOnInvalidIncludedConfigFormat() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "configuration_warnings", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[foo]\nbar = baz\n<file:included.bcfg>", ".buckconfig");
    workspace.writeContentsToPath("[foo]\nbar1=baz1\nno_value", "included.bcfg");

    String expected =
        String.format(
            "parse error (in %s at line 3): no_value",
            tmp.getRoot().resolve("included.bcfg").toString());

    ProcessResult result = workspace.runBuckdCommand("query", "//...").assertFailure();

    assertThat(result.getStderr(), containsString(expected));
  }

  @Test
  public void printsErrorsOnIncludingInvalidPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "configuration_warnings", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[foo]\nbar = baz\n<file:other.bcfg>", ".buckconfig");

    String expected =
        String.format(
            "Error while reading %s: %s (",
            tmp.getRoot().resolve(".buckconfig").toString(),
            tmp.getRoot().resolve("other.bcfg").toString());

    ProcessResult result = workspace.runBuckdCommand("query", "//...").assertFailure();

    assertThat(result.getStderr(), containsString(expected));
  }
}
