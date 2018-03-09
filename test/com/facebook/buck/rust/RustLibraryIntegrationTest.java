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

package com.facebook.buck.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RustLibraryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureRustIsAvailable() throws IOException, InterruptedException {
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

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.containsString("Can't find suitable top-level source file for"));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//messenger:messenger_ambig#rlib").assertFailure();
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
        Matchers.containsString("error: method is never used: `unused`"));
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
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustLibraryRuleCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//messenger:messenger_flags#rlib").getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void libraryCrateRoot() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//messenger2").assertSuccess();
  }

  @Test
  public void binaryWithLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithAliasedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_alias").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }
}
