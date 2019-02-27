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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.WindowsClangUtils;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WindowsClangCxxIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();

  private ProjectWorkspace workspace2;

  private WindowsClangUtils windowsUtils = new WindowsClangUtils();

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.WINDOWS);
    windowsUtils.checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "win_llvm_x64", tmp);
    workspace.setUp();
    workspace2 = TestDataHelper.createProjectWorkspaceForScenario(this, "win_llvm_x64", tmp2);
    workspace2.setUp();
  }

  @Test
  public void simpleBinary64() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app:hello#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("The process is 64bits"));
    assertThat(
        runResult.getStdout(), Matchers.not(Matchers.containsString("The process is WOW64")));
  }

  @Test
  public void simpleBinaryWithLib() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("run", "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("BUCK ON WINDOWS"));
  }

  @Test
  public void simpleBinaryWithThinLib() throws IOException {
    ProcessResult runResult =
        workspace.runBuckCommand(
            "run",
            "-c",
            "cxx#windows-x86_64.archive_contents=thin",
            "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), Matchers.containsString("BUCK ON WINDOWS"));
  }

  @Test
  public void testThinArchives() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckCommand("clean");
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//app_lib:app_lib#windows-x86_64")
        .assertSuccess();
    ImmutableSortedSet<Path> initialObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.obj"));
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//app_lib:app_lib#windows-x86_64")
        .assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally(
            CxxDescriptionEnhancer.createCxxLinkTarget(
                BuildTargetFactory.newInstance("//app_lib:app_lib#windows-x86_64"),
                Optional.empty()));
    ImmutableSortedSet<Path> subsequentObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.obj"));
    assertThat(initialObjects, Matchers.equalTo(subsequentObjects));
  }

  @Test
  public void simpleBinaryIsExecutableByCmd() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app:log");
    runResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app/log/log.txt");
    assertThat(
        workspace.getFileContents(outputPath), Matchers.containsString("The process is 64bits"));
  }

  @Test
  public void simpleBinaryWithAsm64IsExecutableByCmd() throws IOException {
    ProcessResult runResult = workspace.runBuckCommand("build", "//app_asm:log");
    runResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app_asm/log/log.txt");
    assertThat(workspace.getFileContents(outputPath), Matchers.equalToIgnoringCase("42"));
  }

  @Test
  public void asmAndDependencyTracking() throws IOException {
    // no depfile is created for assembly, make sure header tracking is OK with it.
    ProcessResult runResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "cxx.untracked_headers=warn",
            "-c",
            "cxx.detailed_untracked_header_messages=true",
            "//app_asm:log");
    runResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app_asm/log/log.txt");
    assertThat(workspace.getFileContents(outputPath), Matchers.equalToIgnoringCase("42"));
  }

  @Test
  public void testLibIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//lib:lib-out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/lib/lib-out/lib.lib");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "//lib:lib-out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("buck-out/gen/lib/lib-out/lib.lib");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
  }

  @Test
  public void testDllIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//lib:dll-out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/lib/dll-out/lib.dll");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "//lib:dll-out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("buck-out/gen/lib/dll-out/lib.dll");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
  }

  @Test
  public void testExeIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//app_lib:exe-out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app_lib/exe-out/app.exe");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "//app_lib:exe-out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("buck-out/gen/app_lib/exe-out/app.exe");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
  }

  @Test
  public void testXLibIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "x//lib:out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("xplat/buck-out/gen/lib/out/lib.lib");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "x//lib:out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("xplat/buck-out/gen/lib/out/lib.lib");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
  }

  @Test
  public void testXExeIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//app_xlib:exe-out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app_xlib/exe-out/app.exe");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "//app_xlib:exe-out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("buck-out/gen/app_xlib/exe-out/app.exe");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
  }

  @Test
  public void testExeWithAsmIsHermetic() throws IOException {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//app_asm:exe-out");
    buildResult.assertSuccess();
    Path outputPath = workspace.resolve("buck-out/gen/app_asm/exe-out/app_asm.exe");

    ProcessResult buildResult2 = workspace2.runBuckCommand("build", "//app_asm:exe-out");
    buildResult2.assertSuccess();
    Path outputPath2 = workspace2.resolve("buck-out/gen/app_asm/exe-out/app_asm.exe");
    MoreAsserts.assertContentsEqual(outputPath, outputPath2);
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
    Path outputPath = workspace.resolve("buck-out/gen/implib_usage/log/log.txt");
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
        Files.exists(workspace.resolve("buck-out/gen/implib_usage/app_debug#windows-x86_64.pdb")));
    assertTrue(
        Files.exists(
            workspace.resolve(
                "buck-out/gen/implib/implib_debug#shared,windows-x86_64/implib_debug.pdb")));
  }

  private ImmutableSortedSet<Path> findFiles(Path root, PathMatcher matcher) throws IOException {
    ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file)) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files.build();
  }

  @Test
  public void errorVerifyHeaders() throws IOException {
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
            "header_check\\untracked_header.cpp: included an untracked header \"header_check\\untracked_header.h\""));
  }

  @Test
  public void errorVerifyNestedHeaders() throws IOException {
    ProcessResult result;
    result =
        workspace.runBuckBuild(
            "-c",
            "cxx.untracked_headers=error",
            "-c",
            "cxx.untracked_headers_whitelist=/usr/include/stdc-predef\\.h",
            "//header_check:nested_untracked_header#windows-x86_64");
    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "header_check\\nested_untracked_header.cpp: included an untracked header \"header_check\\untracked_header.h\", which is included by:\n"
                + "\t\"header_check\\untracked_header_includer.h\", which is included by:\n"
                + "\t\"header_check\\parent_header.h\""));
  }
}
