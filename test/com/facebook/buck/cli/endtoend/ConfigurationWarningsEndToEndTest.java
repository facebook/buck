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

package com.facebook.buck.cli.endtoend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class ConfigurationWarningsEndToEndTest {

  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  /** Returns a Pattern that's suitable for using built in hamcrest matchers on */
  private Pattern makeStdErrPattern(String pattern) {
    return Pattern.compile(".*^" + pattern + ".*$.*", Pattern.MULTILINE | Pattern.DOTALL);
  }

  private void writeJavaArgsToSetHomeDir(EndToEndWorkspace workspace) throws IOException {
    Files.write(
        workspace.getPath(".buckjavaargs.local"),
        ("-Duser.home=" + workspace.getDestPath()).getBytes());
  }

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment()
        .withCommand("query")
        .withTargets("//...")
        .addTemplates("configuration_warnings");
  }

  @Test
  public void errorMesagesShouldBePrinted(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    workspace.addBuckConfigLocalOption("ui", "warn_on_config_file_overrides", "false");
    workspace.addPremadeTemplate("configuration_warnings");

    ProcessResult result =
        workspace.runBuckCommand(
            ImmutableMap.of("HOME", tmpDir.getRoot().toString()), "query", "//:");

    result.assertSuccess();
    assertThat(
        result.getStderr(), not(containsString("Using additional configuration options from")));
  }

  @Test
  public void filesInIgnoreListAreIgnored(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    workspace.addPremadeTemplate("configuration_warnings");
    workspace.addBuckConfigLocalOption(
        "ui", "warn_on_config_file_overrides_ignored_files", "test_config");
    writeJavaArgsToSetHomeDir(workspace);

    ProcessResult result =
        workspace.runBuckCommand(
            ImmutableMap.of("HOME", tmpDir.getRoot().toString()), "query", "//...");

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
  public void absentIgnoreListShowsAllFiles(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addPremadeTemplate("configuration_warnings");
    workspace.addBuckConfigLocalOption("foo", "bar", "baz1");
    writeJavaArgsToSetHomeDir(workspace);

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
