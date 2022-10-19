/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private static boolean anyContains(String[] lines, String match) {
    Pattern p = Pattern.compile(match);
    for (String line : lines) {
      System.out.println("Testing: " + line);
      Matcher m = p.matcher(line);
      if (m.matches()) {
        System.out.println("Matches!");
        return true;
      }
    }
    return false;
  }

  @Test
  public void rustNoBundlingStaticBuild() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "native_unbundle_deps", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace
        .runBuckBuild("//:cxx_root_static", "-c", "rust.native_unbundle_deps=True")
        .assertSuccess();

    AbsPath ruleGenPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//:left#default,rlib"),
                    "%s"));
    Path ruleOutput =
        Files.find(
                ruleGenPath.getPath(),
                1,
                (p, attrs) ->
                    !attrs.isDirectory() && p.getFileName().toString().contains("libleft"))
            .findFirst()
            .get();

    String[] nmOutput =
        workspace
            .runCommand("nm", ruleOutput.toString())
            .getStdout()
            .get()
            .lines()
            .toArray(String[]::new);
    // Assert that the bottom::bar symbol is undefined
    assertTrue(anyContains(nmOutput, ".* U .*bottom.*bar.*"));

    // Assert that the foo_left symbol is defined
    assertTrue(anyContains(nmOutput, ".* T .*foo_left.*"));

    // Assert that libleft has no knowledge of libright
    assertFalse(anyContains(nmOutput, ".*foo_right.*"));
  }

  @Test
  public void rustNoBundlingSharedBuild() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "native_unbundle_deps", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace
        .runBuckBuild("//:cxx_root_shared", "-c", "rust.native_unbundle_deps=True")
        .assertSuccess();

    AbsPath ruleGenPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//:left#default,dylib"),
                    "%s"));
    Path ruleOutput =
        Files.find(
                ruleGenPath.getPath(),
                1,
                (p, attrs) ->
                    !attrs.isDirectory() && p.getFileName().toString().contains("libleft"))
            .findFirst()
            .get();

    String[] nmOutput =
        workspace
            .runCommand("nm", ruleOutput.toString())
            .getStdout()
            .get()
            .lines()
            .toArray(String[]::new);

    // Assert that the bottom::bar symbol is undefined
    assertTrue(anyContains(nmOutput, ".* U .*bottom.*bar.*"));

    // Assert that the foo_left symbol is defined
    assertTrue(anyContains(nmOutput, ".* T .*foo_left.*"));

    // Assert that libleft has no knowledge of libright
    assertFalse(anyContains(nmOutput, ".*foo_right.*"));
  }

  @Test
  public void rustFlaggedDepsWithCorrectExternBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "flagged_deps", tmp);
    workspace.setUp();

    // Requires nightly or it will complain about `-Z unstable-options`
    // If it's loading `rustup`'s `rustc` set your default toolchain to nightly
    workspace.runBuckBuild("//:foo_with_extern_dep#rlib").assertSuccess();
  }

  @Test
  public void rustFlaggedDepsWithoutCorrectExternBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "flagged_deps", tmp);
    workspace.setUp();

    // Requires nightly or it will complain about `-Z unstable-options`
    // If it's loading `rustup`'s `rustc` set your default toolchain to nightly
    ProcessResult shouldFail = workspace.runBuckBuild("//:foo_without_extern_dep#rlib");
    shouldFail.assertFailure();
    assertThat(
        shouldFail.getStderr(),
        containsString("failed to resolve: use of undeclared crate or module `dep`"));
  }

  @Test
  public void rustPlatformFlaggedDepsWithCorrectExternBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "flagged_deps", tmp);
    workspace.setUp();

    // Requires nightly or it will complain about `-Z unstable-options`
    // If it's loading `rustup`'s `rustc` set your default toolchain to nightly
    workspace.runBuckBuild("//:foo_with_extern_dep_platform#rlib").assertSuccess();
  }

  @Test
  public void rustPlatformFlaggedDepsWithoutCorrectExternBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "flagged_deps", tmp);
    workspace.setUp();

    // Requires nightly or it will complain about `-Z unstable-options`
    // If it's loading `rustup`'s `rustc` set your default toolchain to nightly
    ProcessResult shouldFail = workspace.runBuckBuild("//:foo_without_extern_dep_platform#rlib");
    shouldFail.assertFailure();
    assertThat(
        shouldFail.getStderr(),
        containsString("failed to resolve: use of undeclared crate or module `dep`"));
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
  public void rustLibraryImplicitCheck() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace
        .runBuckBuild("--config", "defaults.rust_library.type=check", "//messenger:messenger")
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
        Matchers.matchesPattern(
            "(?s).*error: (?:method|associated function) is never used: `unused`.*"));
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
  public void rustLibraryCheckPlatformCompilerArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckBuild(
                "--config",
                "cxx#rustc-plugin.dummy=123",
                "--config",
                "rust#rustc-plugin.dummy=123",
                "//messenger:messenger#rlib,rustc-plugin")
            .getStderr(),
        containsString("Unrecognized option: 'using-plugin-flags'"));
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
  public void rustLibraryCompilerTargetTriple() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust#default.rustc_target_triple=fake-target-triple",
                "//messenger:messenger#rlib")
            .getStderr(),
        containsString("Could not find specification for target \"fake-target-triple\"."));
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
  public void libraryRust2015Default() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    workspace
        .runBuckCommand("build", "-c", "rust.default_edition=2015", "//:rust2015-default#check")
        .assertSuccess();
  }

  @Test
  public void libraryRust2018Default() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "editions", tmp);
    workspace.setUp();

    RustAssumptions.assumeVersion(workspace, "1.31");

    workspace
        .runBuckCommand("build", "-c", "rust.default_edition=2018", "//:rust2018-default#check")
        .assertSuccess();
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

  @Test
  public void rustLibraryEnv() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "env_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:env-library#rlib").assertSuccess();
  }

  @Test
  public void rustLibraryDepAlias() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "alias_test", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:alias_in_deps#rlib").assertSuccess();
    workspace.runBuckBuild("//:alias_in_named_deps#rlib").assertSuccess();
  }

  @Test
  public void sharedLibraryCdylibSoname() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "soname", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace.runBuckBuild("//:foo#default,shared").assertSuccess();

    AbsPath ruleGenPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//:foo#default,shared"),
                    "%s"));

    String ruleOutput =
        Files.find(
                ruleGenPath.getPath(),
                1,
                (p, attrs) -> !attrs.isDirectory() && p.getFileName().toString().contains("libfoo"))
            .findFirst()
            .get()
            .toString();

    // .dll and .dylib can't be tested this way
    if (ruleOutput.endsWith(".so")) {
      String[] objdumpOutput =
          workspace
              .runCommand("objdump", "-x", ruleOutput.toString())
              .getStdout()
              .get()
              .lines()
              .toArray(String[]::new);

      // Assert that SONAME is set to `libfoo-<hash>.so`
      assertTrue(anyContains(objdumpOutput, "\\s*SONAME\\s*libfoo.*\\.(?:so|dylib)"));
    }
  }

  @Test
  public void sharedLibraryDylibSoname() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "soname", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace.runBuckBuild("//:foo#default,dylib").assertSuccess();

    AbsPath ruleGenPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance("//:foo#default,dylib"),
                    "%s"));

    String ruleOutput =
        Files.find(
                ruleGenPath.getPath(),
                1,
                (p, attrs) -> !attrs.isDirectory() && p.getFileName().toString().contains("libfoo"))
            .findFirst()
            .get()
            .toString();

    // .dll and .dylib can't be tested this way
    if (ruleOutput.endsWith(".so")) {
      String[] objdumpOutput =
          workspace
              .runCommand("objdump", "-x", ruleOutput.toString())
              .getStdout()
              .get()
              .lines()
              .toArray(String[]::new);

      // Assert that SONAME is set to `libfoo-<hash>.so`
      assertTrue(anyContains(objdumpOutput, "\\s*SONAME\\s*libfoo.*\\.(?:so|dylib)"));
    }
  }
}
