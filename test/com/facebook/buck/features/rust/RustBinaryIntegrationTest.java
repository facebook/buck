/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.rust;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RustBinaryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureRustIsAvailable() {
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

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy#binary,default"), "%s")
            .resolve("xyzzy");
    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Hello, world!"));
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

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy#binary"), "%s")
            .resolve("xyzzy");

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Hello, world!"));
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
    thrown.expectMessage(containsString("No such file or directory"));

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
    thrown.expectMessage(containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy#binary,check,default/xyzzy").toString());
  }

  @Test
  public void simpleBinarySaveAnalysis() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    RustAssumptions.assumeNightly(workspace);

    File output =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy#default,save-analysis"), "%s")
            .resolve("save-analysis/xyzzy-bf3e2606cfd1e9e1.json")
            .toFile();

    workspace.runBuckBuild("//:xyzzy#save-analysis").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy#save-analysis");
    workspace.resetBuildLogFile();

    assertTrue(output.exists());

    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy#binary,save-analysis,default/xyzzy").toString());
  }

  @Test
  public void simpleBinarySaveAnalysisUnflavored() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    RustAssumptions.assumeNightly(workspace);

    File output =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy#default,save-analysis"), "%s")
            .resolve("save-analysis/xyzzy-bf3e2606cfd1e9e1.json")
            .toFile();

    workspace
        .runBuckCommand(
            "build", "--config", "rust.unflavored_binaries=true", "//:xyzzy#save-analysis")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy#save-analysis");
    workspace.resetBuildLogFile();

    assertTrue(output.exists());

    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("No such file or directory"));

    workspace.runCommand(
        workspace.resolve("buck-out/gen/xyzzy#binary,save-analysis,default/xyzzy").toString());
  }

  @Test
  public void simpleBinaryWarnings() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:xyzzy").assertSuccess().getStderr(),
        Matchers.allOf(
            either(containsString("warning: constant item is never used: `foo`"))
                .or(containsString("warning: constant is never used: `foo`")),
            containsString("warning: constant `foo` should have an upper case name")));

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();
  }

  @Test
  public void simpleAliasedBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy_aliased#binary,default"), "%s")
            .resolve("xyzzy");

    workspace.runBuckBuild("//:xyzzy_aliased").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy_aliased");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleCrateRootBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:xyzzy_crate_root#binary,default"), "%s")
            .resolve("xyzzy_crate_root");

    workspace.runBuckBuild("//:xyzzy_crate_root").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy_crate_root");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Another top-level source"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryIncremental() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();
    BuckBuildLog buildLog;

    workspace
        .runBuckCommand("build", "-c", "rust#default.incremental=opt", "//:xyzzy#check")
        .assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy#check");

    workspace
        .runBuckCommand("build", "-c", "rust#default.incremental=dev", "//:xyzzy")
        .assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");

    assertTrue(
        Files.isDirectory(workspace.resolve("buck-out/tmp/rust-incremental/dev/binary/default")));
    assertTrue(
        Files.isDirectory(workspace.resolve("buck-out/tmp/rust-incremental/opt/check/default")));
  }

  @Test
  public void simpleBinaryEdition2015() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bin2015#binary,default"), "%s")
            .resolve("bin2015");

    workspace.runBuckBuild("//:bin2015").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin2015");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Common called"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryEdition2018() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bin2018#binary,default"), "%s")
            .resolve("bin2018");

    workspace.runBuckBuild("//:bin2018").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin2018");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Common called"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryEdition2015Default() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bin2015-default#binary,default"), "%s")
            .resolve("bin2015_default");

    workspace
        .runBuckCommand("build", "-c", "rust.default_edition=2015", "//:bin2015-default")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin2015-default");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Common called"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void simpleBinaryEdition2018Default() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bin2018-default#binary,default"), "%s")
            .resolve("bin2018_default");

    workspace
        .runBuckCommand("build", "-c", "rust.default_edition=2018", "//:bin2018-default")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin2018-default");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("Common called"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithGeneratedSource() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:thing#binary,default"), "%s")
            .resolve("thing");

    workspace.runBuckBuild("//:thing").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:thing");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("info is: this is generated info"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithGeneratedMain() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:generated_top#binary,default"), "%s")
            .resolve("generated_top");

    workspace.runBuckBuild("//:generated_top").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:generated_top");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("generated main, included I WAS INCLUDED"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithGeneratedMainCrateRoot() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(
                BuildTargetFactory.newInstance("//:generated_top_crateroot#binary,default"), "%s")
            .resolve("generated_top_crateroot");

    workspace.runBuckBuild("//:generated_top_crateroot").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:generated_top_crateroot");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("generated main, included I WAS INCLUDED"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithSubdirGeneratedMain() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(
                BuildTargetFactory.newInstance("//subdir:subdir_generated_top#binary,default"),
                "%s")
            .resolve("subdir_generated_top");

    workspace.runBuckBuild("//subdir:subdir_generated_top").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//subdir:subdir_generated_top");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("generated main, included I WAS INCLUDED"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithSubdirGeneratedMainCrateRoot() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(
                BuildTargetFactory.newInstance(
                    "//subdir:subdir_generated_top_crateroot#binary,default"),
                "%s")
            .resolve("subdir_generated_top_crateroot");

    workspace.runBuckBuild("//subdir:subdir_generated_top_crateroot").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//subdir:subdir_generated_top_crateroot");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("generated main, included I WAS INCLUDED"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithGeneratedModule() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:gen_submod#binary,default"), "%s")
            .resolve("gen_submod");

    workspace.runBuckBuild("//:gen_submod").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:gen_submod");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("info: this is generated info"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithSubdirGeneratedModule() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_generated", tmp);
    workspace.setUp();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//subdir:subbin#binary,default"), "%s")
            .resolve("subbin");

    workspace.runBuckBuild("//subdir:subbin").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//subdir:subbin");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("info: this is generated info"));
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
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
  }

  @Test
  public void rustBinaryRuleCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:xyzzy_flags").getStderr(),
        containsString("Unrecognized option: 'this-is-a-bad-option'"));
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
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
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
    thrown.expectMessage(containsString("No such file or directory"));

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
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
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
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithHyphenatedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hyphen").assertSuccess().getStdout(),
        containsString("Hyphenated: Audrey fforbes-Hamilton"));
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

  @Test
  public void binaryWithNamedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello-renamed").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
  }

  @Test
  public void binaryWithMultiNamedLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello-multi-renamed").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"),
            containsString("I have a message to deliver to you"),
            containsString("New thing")));
  }

  @Test
  public void binaryWithStaticCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_static").assertSuccess().getStdout(),
        containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithSharedCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_shared").assertSuccess().getStdout(),
        containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltStaticCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_static").assertSuccess().getStdout(),
        containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithPrebuiltSharedCxxDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_cxx_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:addtest_prebuilt_shared").assertSuccess().getStdout(),
        containsString("10 + 15 = 25"));
  }

  @Test
  public void binaryWithLibraryWithDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library_with_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"),
            containsString("I have a message to deliver to you"),
            containsString("thing handled")));
  }

  @Test
  public void binaryWithLibraryWithTriangleDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library_with_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:transitive").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello from transitive"),
            containsString("I have a message to deliver to you"),
            containsString("thing handled")));
  }

  @Test
  public void featureProvidedWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "feature_test", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:with_feature").assertSuccess().getStdout(),
        containsString("Hello, world!"));
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "feature_test", tmp);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("//:illegal_feature_name");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("contains an invalid feature name"));
  }

  @Test
  public void moduleImportsSuccessfully() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:greeter").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"), containsString("I have a message to deliver to you")));
  }

  @Test
  public void underspecifiedSrcsErrors() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "module_import", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:greeter_fail").assertFailure().getStderr(),
        containsString("file not found for module `messenger`"));
  }

  @Test
  public void binaryWithPrebuiltLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(containsString("Hello, world!"), containsString("plain old foo")));
  }

  @Test
  public void binaryWithAliasedPrebuiltLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_alias").assertSuccess().getStdout(),
        Matchers.allOf(containsString("Hello, world!"), containsString("plain old foo")));
  }

  @Test
  public void binaryWithPrebuiltLibraryWithDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello_foobar").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Hello, world!"),
            containsString("this is foo, and here is my friend bar"),
            containsString("plain old bar")));
  }

  @Test
  public void cxxWithRustDependencyStatic() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Calling helloer"),
            containsString("I'm printing hello!"),
            containsString("Helloer called")));
  }

  @Test
  public void cxxWithRustDependencyShared() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello-shared").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("Calling helloer"),
            containsString("I'm printing hello!"),
            containsString("Helloer called")));
  }

  @Test
  public void cxxBinaryWithSharedRustDependencyExecutesOutsideProjectDirectory()
      throws IOException, java.lang.InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_with_rust_dep", tmp);
    workspace.setUp();

    Path programPath = workspace.buildAndReturnOutput("//:hello-shared");
    java.lang.Process process =
        new java.lang.ProcessBuilder(new String[] {programPath.toString()})
            .directory(programPath.getRoot().toFile())
            .start();
    assertThat(process.waitFor(), Matchers.equalTo(0));
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
            containsString("Calling helloer"),
            containsString("I'm printing hello!"),
            containsString("Helloer called")));
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
            containsString("I am top"),
            containsString("I am mid, calling thing\nthing2"),
            containsString("thing1")));
  }

  @Test
  public void duplicateSharedCrateName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "duplicate_crate", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:top_shared").assertSuccess().getStdout(),
        Matchers.allOf(
            containsString("I am top"),
            containsString("I am mid, calling thing\nthing2"),
            containsString("thing1")));
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
        containsString("Hello"));
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
        containsString("Hello"));
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
        containsString("Hello"));
  }

  @Test
  public void simpleBinaryCrossCell() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multi_cell", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--config", "rust.unflavored_binaries=true", "cell//:thinguser")
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("cell//:thinguser");
    workspace.resetBuildLogFile();

    String binary =
        "cell/"
            + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//:thinguser#binary"),
                    "%s")
                .resolve("thinguser");

    workspace.runCommand(binary);
  }

  @Test
  public void simpleBinaryWithMacroExpansion() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "macro_args", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:binary-args").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:binary-args");
    workspace.resetBuildLogFile();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:binary-args#binary,default"), "%s")
            .resolve("binary_args");

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.blankString());
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void rustBinaryEnv() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "env_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:env-binary").assertSuccess();

    Path binary =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:env-binary#binary,default"), "%s")
            .resolve("env_binary");

    ProcessExecutor.Result result = workspace.runCommand(binary.toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), containsString("My FOO something else"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }
}
