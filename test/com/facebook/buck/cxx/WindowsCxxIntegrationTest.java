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
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

public class WindowsCxxIntegrationTest {
  private static String clExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\cl.exe";

  private static String linkExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\link.exe";

  private static String libExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\lib.exe";

  private static String[] includeDirs = new String[] {
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\include",
      "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\ucrt",
      "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\um",
      "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\shared"
  };

  private static String[] libDirs = new String[] {
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\LIB\\amd64",
      "C:\\Program Files (x86)\\Windows Kits\\10\\lib\\10.0.10586.0\\ucrt\\x64",
      "C:\\Program Files (x86)\\Windows Kits\\10\\lib\\10.0.10586.0\\um\\x64",
  };


  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.WINDOWS);
    checkAssumptions();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "win_x64", tmp);
    workspace.setUp();
    Escaper.Quoter quoter = Escaper.Quoter.DOUBLE_WINDOWS_JAVAC;
    workspace.replaceFileContents(
        ".buckconfig",
        "$CL_EXE$",
        quoter.quote(clExe)
    );
    workspace.replaceFileContents(
        ".buckconfig",
        "$LIB_EXE$",
        quoter.quote(libExe)
    );
    workspace.replaceFileContents(
        ".buckconfig",
        "$LINK_EXE$",
        quoter.quote(linkExe)
    );
    workspace.replaceFileContents(
        "BUILD_DEFS",
        "$WINDOWS_COMPILE_FLAGS$",
        Arrays.stream(includeDirs).map(
            s -> quoter.quote("/I" + s)).collect(Collectors.joining(", "))
    );
    workspace.replaceFileContents(
        "BUILD_DEFS",
        "$WINDOWS_LINK_FLAGS$",
        Arrays.stream(libDirs).map(
            s -> quoter.quote("/LIBPATH:" + s)).collect(Collectors.joining(", "))
    );
  }

  @Test
  public void simpleBinary64() throws IOException {
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "run",
            "//app:hello#windows-x86_64");
    runResult.assertSuccess();
    assertThat(
        runResult.getStdout(),
        Matchers.containsString("The process is 64bits"));
    assertThat(
        runResult.getStdout(),
        Matchers.not(Matchers.containsString("The process is WOW64")));
  }

  @Test
  public void simpleBinaryWithLib() throws IOException {
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "run",
            "//app_lib:app_lib#windows-x86_64");
    runResult.assertSuccess();
    assertThat(
        runResult.getStdout(),
        Matchers.containsString("BUCK ON WINDOWS"));
  }

  private static void checkAssumptions() {
    assumeTrue(
        "cl.exe should exist",
        Files.isExecutable(Paths.get(clExe)));

    assumeTrue(
        "link.exe should exist",
        Files.isExecutable(Paths.get(linkExe)));

    assumeTrue(
        "lib.exe should exist",
        Files.isExecutable(Paths.get(libExe)));

    for (String includeDir : includeDirs) {
      assumeTrue(
          String.format("include dir %s should exist", includeDir),
          Files.isDirectory(Paths.get(includeDir)));
    }

    for (String libDir : libDirs) {
      assumeTrue(
          String.format("lib dir %s should exist", libDir),
          Files.isDirectory(Paths.get(libDir)));
    }
  }
}
