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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.WindowsUtils;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WindowsCxxIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;


  private WindowsUtils windowsUtils = new WindowsUtils();

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.WINDOWS);
    windowsUtils.checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "win_x64", tmp);
    workspace.setUp();
  }

  @Test
  public void testFilenameIsFilteredOut() {
    ProcessResult runResult = workspace.runBuckCommand("build", "//simple:simple#windows-x86_64");
    runResult.assertSuccess();
    final String input = "simple.cpp";
    assertThat(
        runResult.getStderr().split("\n"),
        Matchers.not(Matchers.hasItemInArray(Matchers.matchesPattern(input + "\\s*"))));
    assertThat(
        runResult.getStdout().split("\n"),
        Matchers.not(Matchers.hasItemInArray(Matchers.matchesPattern(input + "\\s*"))));
  }

  @Test
  public void simpleBinary64() {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app:hello#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("The process is 64bits"));
    assertThat(
        runResult.getStdout(), Matchers.not(Matchers.containsString("The process is WOW64")));
  }

  @Test
  public void simpleBinary64WithDebugFull() {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app:pdb");
    runResult.assertSuccess();
  }

  @Test
  public void simpleBinaryWithLib() {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("BUCK ON WINDOWS"));
  }

  @Test
  public void simpleBinaryWithWholeLib() {
    ProcessResult runResult =
        workspace.runBuckCommand("run", "//app_wholelib:app_wholelib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("hello from lib"));
  }

  @Test
  public void simpleBinaryIsExecutableByCmd() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app:log");
    runResult.assertSuccess();
    Path outputPath =
        workspace
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//app:log"),
                    "%s"))
            .resolve("log.txt");
    assertThat(
        workspace.getFileContents(outputPath), Matchers.containsString("The process is 64bits"));
  }

  @Test
  public void simpleBinaryWithAsm64IsExecutableByCmd() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app_asm:log");
    runResult.assertSuccess();
    Path outputPath =
        workspace.resolve(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//app_asm:log"),
                    "%s")
                .resolve("log.txt"));
    assertThat(workspace.getFileContents(outputPath), Matchers.equalToIgnoringCase("42"));
  }


  @Test
  public void simpleBinaryWithDll() throws IOException {
    ProcessResult libResult =
        workspace.runBuckCommand("build", "//implib:implib#windows-x86_64,shared");
    libResult.assertSuccess();

    ProcessResult appResult =
        workspace.runBuckCommand("build", "//implib_usage:app#windows-x86_64");
    appResult.assertSuccess();

    ProcessResult runResult = workspace.runBuckCommand("run", "//implib_usage:app#windows-x86_64");
    runResult.assertSuccess();

    ProcessResult logResult = workspace.runBuckCommand("build", "//implib_usage:log");
    logResult.assertSuccess();
    Path outputPath =
        workspace
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//implib_usage:log"),
                    "%s"))
            .resolve("log.txt");
    assertThat(workspace.getFileContents(outputPath), Matchers.containsString("a + (a * b)"));
  }

  @Test
  public void pdbFilesAreCached() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckCommand("build", "//implib_usage:app_debug#windows-x86_64").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", "//implib_usage:app_debug#windows-x86_64").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetWasFetchedFromCache("//implib:implib_debug#windows-x86_64,shared");
    buildLog.assertTargetWasFetchedFromCache("//implib_usage:app_debug#windows-x86_64,binary");
    assertTrue(
        Files.exists(
            workspace.resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//implib_usage:app_debug#windows-x86_64"),
                    "%s.pdb"))));
    assertTrue(
        Files.exists(
            workspace
                .resolve(
                    BuildTargetPaths.getGenPath(
                        workspace.getProjectFileSystem(),
                        BuildTargetFactory.newInstance(
                            "//implib:implib_debug#windows-x86_64,shared"),
                        "%s"))
                .resolve("implib_debug.pdb")));
  }

  @Test
  public void implibOutputAccessible() throws IOException {
    workspace.runBuckCommand("build", "//implib:implib_copy").assertSuccess();
    assertTrue(
        Files.exists(
            workspace
                .resolve(
                    BuildTargetPaths.getGenPath(
                        workspace.getProjectFileSystem(),
                        BuildTargetFactory.newInstance("//implib:implib_copy"),
                        "%s"))
                .resolve("implib_copy.lib")));
  }

  @Test
  public void simpleBinaryWithPrebuiltDll() throws IOException {
    ProcessResult appResult =
        workspace.runBuckCommand("build", "//implib_prebuilt:app#windows-x86_64");
    appResult.assertSuccess();

    ProcessResult runResult =
        workspace.runBuckCommand("run", "//implib_prebuilt:app#windows-x86_64");
    runResult.assertSuccess();

    ProcessResult logResult = workspace.runBuckCommand("build", "//implib_prebuilt:log");
    logResult.assertSuccess();
    Path outputPath =
        workspace
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//implib_prebuilt:log"),
                    "%s"))
            .resolve("log.txt");
    String outputPathContents = workspace.getFileContents(outputPath);
    assertThat(outputPathContents, Matchers.containsString("a + (a * b)"));
    assertThat(outputPathContents, Matchers.containsString("Hello, world!"));
  }

  @Test
  public void simpleCrossCellBinaryWithPrebuiltDll() throws IOException {
    ProcessResult appResult =
        workspace.runBuckCommand("build", "implib_prebuilt_cell2//:app#windows-x86_64");
    appResult.assertSuccess();

    ProcessResult runResult =
        workspace.runBuckCommand("run", "implib_prebuilt_cell2//:app#windows-x86_64");
    runResult.assertSuccess();

    ProcessResult logResult = workspace.runBuckCommand("build", "implib_prebuilt_cell2//:log");
    logResult.assertSuccess();
    Path outputPath =
        workspace
            .resolve("implib_prebuilt/cell2/buck-out/gen")
            .resolve(
                BuildTargetPaths.getBasePath(
                        workspace.getProjectFileSystem(),
                        BuildTargetFactory.newInstance("implib_prebuilt_cell2//:log"),
                        "%s")
                    .toString())
            .resolve("log.txt");
    assertThat(workspace.getFileContents(outputPath), Matchers.containsString("a + (a * b)"));
  }

  @Test
  public void errorVerifyHeaders() {
    ProcessResult result;
    result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=/usr/include/stdc-predef\\.h",
            "//header_check:untracked_header#windows-x86_64");
    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            String.format(
                "header_check\\untracked_header.cpp: included an untracked header: %n"
                    + "header_check\\untracked_header.h")));
  }

  @Test
  public void errorVerifyNestedHeaders() {
    ProcessResult result;
    result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=/usr/include/stdc-predef\\.h",
            "-c",
            "cxx.detailed_untracked_header_messages=true",
            "//header_check:nested_untracked_header#windows-x86_64");
    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            String.format(
                "header_check\\nested_untracked_header.cpp: included an untracked header: %n"
                    + "header_check\\untracked_header.h, which is included by: %n"
                    + "header_check\\untracked_header_includer.h, which is included by: %n"
                    + "header_check\\parent_header.h")));
  }

  @Test
  public void errorVerifyNestedHeadersWithCycle() {
    ProcessResult result;
    result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=/usr/include/stdc-predef\\.h",
            "-c",
            "cxx.detailed_untracked_header_messages=true",
            "//header_check:nested_untracked_header_with_cycle#windows-x86_64");
    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            String.format(
                "header_check\\nested_untracked_header_with_cycle.cpp: included an untracked header: %n"
                    + "header_check\\untracked_header.h, which is included by: %n"
                    + "header_check\\untracked_header_includer.h, which is included by: %n"
                    + "header_check\\parent_header.h")));
  }

  @Test
  public void compilationDatabaseCanBeBuilt() {
    workspace.runBuckBuild("//app:hello#compilation-database,windows-x86_64").assertSuccess();
    workspace.runBuckBuild("//app_asm:app_asm#compilation-database,windows-x86_64").assertSuccess();
    workspace.runBuckBuild("//app_lib:app_lib#compilation-database,windows-x86_64").assertSuccess();
    workspace.runBuckBuild("//lib:lib#compilation-database,windows-x86_64,static").assertSuccess();
    workspace.runBuckBuild("//lib:lib#compilation-database,windows-x86_64,shared").assertSuccess();
    workspace.runBuckBuild("//lib:lib#compilation-database,windows-x86_64").assertSuccess();
  }
}
