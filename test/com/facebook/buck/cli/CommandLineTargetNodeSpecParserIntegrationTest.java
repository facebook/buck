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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class CommandLineTargetNodeSpecParserIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void trailingDotDotDotBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_line_parser", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//simple/...").assertSuccess();
    ImmutableSet<BuildTarget> targets =
        ImmutableSet.of(
            BuildTargetFactory.newInstance("//simple:simple"),
            BuildTargetFactory.newInstance("//simple/foo:foo"),
            BuildTargetFactory.newInstance("//simple/bar:bar"));
    for (BuildTarget target : targets) {
      workspace.getBuildLog().assertTargetBuiltLocally(target.toString());
    }
    assertEquals(targets, workspace.getBuildLog().getAllTargets());
  }

  @Test
  public void trailingDotDotDotTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_line_parser", tmp);
    workspace.setUp();

    // First check for a correct usage.
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "//simple/...").assertSuccess();
    assertEquals(
        ImmutableSet.of("//simple:simple", "//simple/foo:foo", "//simple/bar:bar"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));

    // Check for some expected failure cases.
    try {
      workspace.runBuckCommand("targets", "//simple:...");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "No rule found when resolving target //simple:... in build file //simple/BUCK"));
    }
    try {
      workspace.runBuckCommand("targets", "//simple/....");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "No build file at simple/..../BUCK when resolving target //simple/....:....."));
    }
    try {
      workspace.runBuckCommand("targets", "//simple/.....");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "No build file at simple/...../BUCK when resolving target //simple/.....:......"));
    }
    try {
      workspace.runBuckCommand("targets", "//simple/......");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "No build file at simple/....../BUCK when resolving target //simple/......:......."));
    }
  }

  @Test
  public void trailingColonBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_line_parser", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//simple:").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//simple:simple");
    assertEquals(
        ImmutableSet.of(BuildTargetFactory.newInstance("//simple:simple")),
        workspace.getBuildLog().getAllTargets());
  }

  @Test
  public void trailingColonTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_line_parser", tmp);
    workspace.setUp();

    // First check for correct usage.
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "//simple:").assertSuccess();
    assertEquals(
        ImmutableSet.of("//simple:simple"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));

    // Check for a failure case.
    try {
      workspace.runBuckCommand("targets", "//simple:.");
      fail("exepcted to fail");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "No rule found when resolving target //simple:. in build file //simple/BUCK"));
    }
  }

  @Test
  public void ignorePaths() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_line_parser", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[project]\n  ignore = ignored", ".buckconfig");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "...").assertSuccess();
    assertEquals(
        ImmutableSet.of("//simple:simple", "//simple/foo:foo", "//simple/bar:bar"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));
  }

}
