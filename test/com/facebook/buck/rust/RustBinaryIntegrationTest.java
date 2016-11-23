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

package com.facebook.buck.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;

public class RustBinaryIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureRustIsAvailable() throws IOException, InterruptedException {
    RustAssumptions.assumeRustCompilerAvailable();
  }

  @Test
  public void simpleBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryWarnings() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(workspace.runBuckBuild("//:xyzzy").assertSuccess().getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "warning: constant item is never used: `foo`, #[warn(dead_code)] on by default"),
            Matchers.containsString(
                "warning: constant `foo` should have an upper case name such as `FOO`,")));

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();
  }

  @Test
  public void simpleAliasedBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy_aliased").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy_aliased");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy_aliased/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void rustBinaryCompilerArgs() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust.rustc_flags=--this-is-a-bad-option",
                "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'."));
  }

  @Test
  public void rustBinaryCompilerBinaryArgs() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust.rustc_binary_flags=--this-is-a-bad-option",
                "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'."));
  }

  @Test
  public void rustBinaryCompilerLibraryArgs() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "run",
            "--config",
            "rust.rustc_library_flags=--this-is-a-bad-option",
            "//:xyzzy")
        .assertSuccess();
  }

  @Test
  public void rustBinaryCompilerArgs2() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust.rustc_flags=--verbose --this-is-a-bad-option",
                "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'."));
  }

  @Test
  public void rustBinaryRuleCompilerArgs() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "//:xyzzy_flags")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'."));
  }

  @Test
  public void buildAfterChangeWorks() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    workspace.writeContentsToPath(
        workspace.getFileContents("main.rs") + "// this is a comment",
        "main.rs");
  }

  @Test
  public void binaryWithStaticCxxDep() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_static").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithSharedCxxDep() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_shared").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltStaticCxxDep() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_static").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltSharedCxxDep() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_shared").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithLibraryWithDep() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_library_with_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you"),
            Matchers.containsString("thing handled")));
  }

  @Test
  public void featureProvidedWorks() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "feature_test", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:with_feature").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void featureNotProvidedFails() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "feature_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:without_feature").assertFailure();
  }

  @Test
  public void featureWithDoubleQuoteErrors() throws IOException, InterruptedException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.containsString("contains an invalid feature name"));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "feature_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:illegal_feature_name").assertFailure();
  }

  @Test
  public void moduleImportsSuccessfully() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:greeter").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }

  @Test
  public void underspecifiedSrcsErrors() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:greeter_fail").assertFailure().getStderr(),
        Matchers.containsString("file not found for module `messenger`"));
  }

  @Test
  public void binaryWithPrebuiltLibrary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("plain old foo")));
  }

  @Test
  public void binaryWithAliasedPrebuiltLibrary() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_alias").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("plain old foo")));
  }

  @Test
  public void binaryWithPrebuiltLibraryWithDependency() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_foobar").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("this is foo, and here is my friend bar"),
            Matchers.containsString("plain old bar")));
  }
}
