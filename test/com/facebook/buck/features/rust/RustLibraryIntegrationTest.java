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

package com.facebook.buck.features.rust;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class RustLibraryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void ensureRustIsAvailable() {
    RustAssumptions.assumeRustIsConfigured();
  }

  @Test
  public void rustLibraryBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//messenger:messenger#rlib").assertSuccess();
  }

  @Test
  public void rustLibraryAmbigFail() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("//messenger:messenger_ambig#rlib");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(), containsString("Can't find suitable top-level source file for"));
  }

  @Test
  public void rustLibraryAmbigOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//messenger:messenger_ambig_ovr#rlib").assertSuccess();
  }

  @Test
  public void rustLibraryCheck() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace
        .runBuckBuild(
            "--config", "rust.rustc_check_flags=-Dwarnings", "//messenger:messenger#check")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//messenger:messenger#check,default");
    workspace.resetBuildLogFile();
  }

  @Test
  public void rustLibraryCheckWarning() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckBuild(
                "--config",
                "rust.rustc_check_flags=-Dwarnings --cfg \"feature=\\\"warning\\\"\"",
                "//messenger:messenger#check")
            .getStderr(),
        containsString("error: method is never used: `unused`"));
  }

  @Test
  public void rustLibraryCheckCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckBuild(
                "--config",
                "rust.rustc_check_flags=--this-is-a-bad-option",
                "//messenger:messenger#check")
            .getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustLibrarySaveAnalysis() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    RustAssumptions.assumeNightly(workspace);

    workspace
        .runBuckBuild(
            "--config", "rust.rustc_check_flags=-Dwarnings", "//messenger:messenger#save-analysis")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//messenger:messenger#save-analysis,default");
    workspace.resetBuildLogFile();
  }

  @Test
  public void rustLibraryCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckBuild(
                "--config", "rust.rustc_flags=--this-is-a-bad-option", "//messenger:messenger#rlib")
            .getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustLibraryCompilerLibraryArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckBuild(
                "--config",
                "rust.rustc_library_flags=--this-is-a-bad-option",
                "//messenger:messenger#rlib")
            .getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustLibraryCompilerBinaryArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace
        .runBuckBuild(
            "--config", "rust.rustc_binary_flags=--this-is-a-bad-option", "//messenger:messenger")
        .assertSuccess();
  }

  @Test
  public void rustLibraryCompilerArgs2() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust.rustc_flags=--verbose --this-is-a-bad-option",
                "//messenger:messenger#rlib")
            .getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustLibraryRuleCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//messenger:messenger_flags#rlib").getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void libraryCrateRoot() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//messenger2").assertSuccess();
  }

  @Test
  public void libraryRust2015() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    workspace.runBuckBuild("//:rust2015#check").assertSuccess();
  }

  @Test
  public void libraryRust2018() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    workspace.runBuckBuild("//:rust2018#check").assertSuccess();
  }

  @Test
  public void binaryWithLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithAliasedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_alias").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
  }
}
