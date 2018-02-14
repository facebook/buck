/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.io;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ConfiguredBuckOutIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "configured_buck_out", tmp);
    workspace.setUp();
  }

  @Test
  public void outputPathsUseConfiguredBuckOut() throws IOException {
    String buckOut = "new-buck-out";
    Path output = workspace.buildAndReturnOutput("-c", "project.buck_out=" + buckOut, "//:dummy");
    assertTrue(Files.exists(output));
    assertThat(workspace.getDestPath().relativize(output).toString(), Matchers.startsWith(buckOut));
  }

  @Test
  public void configuredBuckOutAffectsRuleKey() throws IOException {
    String out =
        workspace
            .runBuckCommand("targets", "--show-rulekey", "//:dummy")
            .assertSuccess()
            .getStdout();
    String ruleKey = Splitter.on(' ').splitToList(out).get(1);
    String configuredOut =
        workspace
            .runBuckCommand(
                "targets", "--show-rulekey", "-c", "project.buck_out=something", "//:dummy")
            .assertSuccess()
            .getStdout();
    String configuredRuleKey = Splitter.on(' ').splitToList(configuredOut).get(1);
    assertThat(ruleKey, Matchers.not(Matchers.equalTo(configuredRuleKey)));
  }

  @Test
  public void buckOutCompatSymlink() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));
    ProcessResult result =
        workspace.runBuckBuild(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy");
    result.assertSuccess();
    assertThat(
        Files.readSymbolicLink(workspace.resolve("buck-out/gen")),
        Matchers.equalTo(workspace.getDestPath().getFileSystem().getPath("../something/gen")));
  }

  @Test
  public void verifyTogglingConfiguredBuckOut() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));
    workspace
        .runBuckBuild(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy")
        .assertSuccess();
    workspace.runBuckBuild("//:dummy").assertSuccess();
    workspace
        .runBuckBuild(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy")
        .assertSuccess();
  }

  @Test
  public void verifyNoopBuildWithCompatSymlink() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));

    // Do an initial build.
    workspace
        .runBuckBuild(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy")
        .assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:dummy");

    // Run another build immediately after and verify everything was up to date.
    workspace
        .runBuckBuild(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy")
        .assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:dummy");
  }

  @Test
  public void targetsShowOutput() throws IOException {
    String output =
        workspace
            .runBuckCommand(
                "targets", "--show-output", "-c", "project.buck_out=something", "//:dummy")
            .assertSuccess()
            .getStdout()
            .trim();
    output = Splitter.on(' ').splitToList(output).get(1);
    assertThat(MorePaths.pathWithUnixSeparators(output), Matchers.startsWith("something/"));
  }

  @Test
  public void targetsShowOutputCompatSymlink() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));
    String output =
        workspace
            .runBuckCommand(
                "targets",
                "--show-output",
                "-c",
                "project.buck_out=something",
                "-c",
                "project.buck_out_compat_link=true",
                "//:dummy")
            .assertSuccess()
            .getStdout()
            .trim();
    output = Splitter.on(' ').splitToList(output).get(1);
    assertThat(MorePaths.pathWithUnixSeparators(output), Matchers.startsWith("buck-out/gen/"));
  }

  @Test
  public void buildShowOutput() throws IOException {
    Path output = workspace.buildAndReturnOutput("-c", "project.buck_out=something", "//:dummy");
    assertThat(
        MorePaths.pathWithUnixSeparators(workspace.getDestPath().relativize(output)),
        Matchers.startsWith("something/"));
  }

  @Test
  public void buildShowOutputCompatSymlink() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));
    Path output =
        workspace.buildAndReturnOutput(
            "-c",
            "project.buck_out=something",
            "-c",
            "project.buck_out_compat_link=true",
            "//:dummy");
    assertThat(
        MorePaths.pathWithUnixSeparators(workspace.getDestPath().relativize(output)),
        Matchers.startsWith("buck-out/gen/"));
  }
}
