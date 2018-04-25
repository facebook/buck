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

package com.facebook.buck.features.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RustBinaryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureRustIsAvailable() throws IOException, InterruptedException {
    RustAssumptions.assumeRustIsConfigured();
  }

  @Test
  public void simpleBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace.resolve("buck-out/gen/xyzzy#binary,default/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryUnflavored() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--config", "rust.unflavored_binaries=true", "//:xyzzy")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(workspace.resolve("buck-out/gen/xyzzy#binary/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryCheck() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy#check").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy#check");
    workspace.resetBuildLogFile();

    thrown.expect(IOException.class);
    thrown.expectMessage(Matchers.containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy#binary,check,default/xyzzy").toString());
  }

  @Test
  public void simpleBinaryCheckUnflavored() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--config", "rust.unflavored_binaries=true", "//:xyzzy#check")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy#check");
    workspace.resetBuildLogFile();

    thrown.expect(IOException.class);
    thrown.expectMessage(Matchers.containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy#binary,check,default/xyzzy").toString());
  }

  @Test
  public void simpleBinaryWarnings() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:xyzzy").assertSuccess().getStderr(),
        Matchers.allOf(
            Matchers.containsString("warning: constant item is never used: `foo`"),
            Matchers.containsString(
                "warning: constant `foo` should have an upper case name such as `FOO`")));

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();
  }

  @Test
  public void simpleAliasedBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy_aliased").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy_aliased");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace.resolve("buck-out/gen/xyzzy_aliased#binary,default/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleCrateRootBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy_crate_root").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy_crate_root");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace
                .resolve("buck-out/gen/xyzzy_crate_root#binary,default/xyzzy_crate_root")
                .toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Another top-level source"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithGeneratedSource() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:thing").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:thing");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace.resolve("buck-out/gen/thing#binary,default/thing").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(
        result.getStdout().get(), Matchers.containsString("info is: this is generated info"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void rustBinaryCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run", "--config", "rust.rustc_flags=--this-is-a-bad-option", "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustBinaryCompilerBinaryArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run", "--config", "rust.rustc_binary_flags=--this-is-a-bad-option", "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustBinaryCompilerLibraryArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "run", "--config", "rust.rustc_library_flags=--this-is-a-bad-option", "//:xyzzy")
        .assertSuccess();
  }

  @Test
  public void rustBinaryCompilerArgs2() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run", "--config", "rust.rustc_flags=--verbose --this-is-a-bad-option", "//:xyzzy")
            .getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustBinaryRuleCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:xyzzy_flags").getStderr(),
        Matchers.containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void buildAfterChangeWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    workspace.writeContentsToPath(
        workspace.getFileContents("main.rs") + "// this is a comment", "main.rs");
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
  public void binaryWithLibraryCheck() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:hello#check").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:hello#check");
    workspace.resetBuildLogFile();

    // XXX check messenger.rmeta exists

    thrown.expect(IOException.class);
    thrown.expectMessage(Matchers.containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/hello#binary,check,default/hello").toString());
  }

  @Test
  public void binaryWithSharedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello-shared").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithSharedLibraryForceRlib() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "-c", "rust.force_rlib=true", "//:hello-shared")
            .assertSuccess()
            .getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithHyphenatedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hyphen").assertSuccess().getStdout(),
        Matchers.containsString("Hyphenated: Audrey fforbes-Hamilton"));
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

  @Test
  public void binaryWithStaticCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_static").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithSharedCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_shared").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltStaticCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_static").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltSharedCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_shared").assertSuccess().getStdout(),
        Matchers.containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithLibraryWithDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library_with_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you"),
            Matchers.containsString("thing handled")));
  }

  @Test
  public void binaryWithLibraryWithTriangleDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library_with_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:transitive").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello from transitive"),
            Matchers.containsString("I have a message to deliver to you"),
            Matchers.containsString("thing handled")));
  }

  @Test
  public void featureProvidedWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "feature_test", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:with_feature").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void featureNotProvidedFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "feature_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:without_feature").assertFailure();
  }

  @Test
  public void featureWithDoubleQuoteErrors() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.containsString("contains an invalid feature name"));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "feature_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:illegal_feature_name").assertFailure();
  }

  @Test
  public void moduleImportsSuccessfully() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:greeter").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("I have a message to deliver to you")));
  }

  @Test
  public void underspecifiedSrcsErrors() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:greeter_fail").assertFailure().getStderr(),
        Matchers.containsString("file not found for module `messenger`"));
  }

  @Test
  public void binaryWithPrebuiltLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"), Matchers.containsString("plain old foo")));
  }

  @Test
  public void binaryWithAliasedPrebuiltLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_alias").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"), Matchers.containsString("plain old foo")));
  }

  @Test
  public void binaryWithPrebuiltLibraryWithDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_foobar").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Hello, world!"),
            Matchers.containsString("this is foo, and here is my friend bar"),
            Matchers.containsString("plain old bar")));
  }

  @Test
  public void cxxWithRustDependencyStatic() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Calling helloer"),
            Matchers.containsString("I'm printing hello!"),
            Matchers.containsString("Helloer called")));
  }

  @Test
  public void cxxWithRustDependencyShared() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello-shared").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("Calling helloer"),
            Matchers.containsString("I'm printing hello!"),
            Matchers.containsString("Helloer called")));
  }

  @Test
  public void cxxWithRustDependencySharedForceRlib() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "-c", "rust.force_rlib=true", "//:hello-shared")
            .assertSuccess()
            .getStdout(),
        Matchers.allOf(
            Matchers.containsString("Calling helloer"),
            Matchers.containsString("I'm printing hello!"),
            Matchers.containsString("Helloer called")));
  }

  @Test
  public void duplicateCrateName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "duplicate_crate", tmp);
    workspace.setUp();

    assertThat(
        // Check that the build works with crates with duplicate names
        workspace
            .runBuckCommand("run", "//:top")
            .assertSuccess("link with duplicate crate names")
            .getStdout(),
        // Make sure we actually get the distinct crates we wanted.
        Matchers.allOf(
            Matchers.containsString("I am top"),
            Matchers.containsString("I am mid, calling thing\nthing2"),
            Matchers.containsString("thing1")));
  }

  @Test
  public void duplicateSharedCrateName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "duplicate_crate", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:top_shared").assertSuccess().getStdout(),
        Matchers.allOf(
            Matchers.containsString("I am top"),
            Matchers.containsString("I am mid, calling thing\nthing2"),
            Matchers.containsString("thing1")));
  }

  @Test
  public void includeFileIncluded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_include", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:include_included").assertSuccess().getStdout(),
        Matchers.matchesPattern(
            "^Got included stuff: /.*/buck-out/.*/included\\.rs\n"
                + "subinclude has /.*/buck-out/.*/subdir/subinclude\\.rs\n$"));
  }

  @Test
  public void includeFileMissing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_include", tmp);
    workspace.setUp();

    workspace.runBuckCommand("run", "//:include_missing").assertFailure();
  }

  @Test
  public void procmacroCompile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "procmacro", tmp);
    workspace.setUp();

    assertThat(
        // Check that we can build a procmacro crate
        workspace.runBuckCommand("run", "//:test").assertSuccess("link with procmacro").getStdout(),
        // Make sure we get a working executable.
        Matchers.containsString("Hello"));
  }

  @Test
  public void procmacroCompileCheck() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "procmacro", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:test#check").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:test#check");
  }

  @Test
  public void procmacroCompileShared() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "procmacro", tmp);
    workspace.setUp();

    assertThat(
        // Check that we can build a procmacro crate
        workspace
            .runBuckCommand("run", "//:test_shared")
            .assertSuccess("link with procmacro")
            .getStdout(),
        // Make sure we get a working executable
        Matchers.containsString("Hello"));
  }

  @Test
  public void procmacroCompileSharedForceRlib() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "procmacro", tmp);
    workspace.setUp();

    assertThat(
        // Check that we can build a procmacro crate
        workspace
            .runBuckCommand("run", "-c", "rust.force_rlib=true", "//:test_shared")
            .assertSuccess("link with procmacro")
            .getStdout(),
        // Make sure we get a working executable
        Matchers.containsString("Hello"));
  }
}
