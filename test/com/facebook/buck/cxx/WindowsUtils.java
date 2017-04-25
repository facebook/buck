/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.Escaper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

public class WindowsUtils {
  private static String clExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\cl.exe";

  private static String linkExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\link.exe";

  private static String libExe =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\lib.exe";

  private static String[] includeDirs =
      new String[] {
        "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\include",
        "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\ucrt",
        "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\um",
        "C:\\Program Files (x86)\\Windows Kits\\10\\Include\\10.0.10586.0\\shared"
      };

  private static String[] libDirs =
      new String[] {
        "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\LIB\\amd64",
        "C:\\Program Files (x86)\\Windows Kits\\10\\lib\\10.0.10586.0\\ucrt\\x64",
        "C:\\Program Files (x86)\\Windows Kits\\10\\lib\\10.0.10586.0\\um\\x64",
      };

  public static String vcvarsallBat =
      "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\vcvarsall.bat";

  private WindowsUtils() {}

  static void setUpWorkspace(ProjectWorkspace workspace, String... cells) throws IOException {
    Escaper.Quoter quoter = Escaper.Quoter.DOUBLE_WINDOWS_JAVAC;
    for (int i = -1; i < cells.length; i++) {
      String prefix = i == -1 ? "" : cells[i] + "/";
      String buckconfig = prefix + ".buckconfig";
      String buildDefs = prefix + "BUILD_DEFS";
      workspace.replaceFileContents(buckconfig, "$CL_EXE$", quoter.quote(clExe));
      workspace.replaceFileContents(buckconfig, "$LIB_EXE$", quoter.quote(libExe));
      workspace.replaceFileContents(buckconfig, "$LINK_EXE$", quoter.quote(linkExe));
      workspace.replaceFileContents(
          buildDefs,
          "$WINDOWS_COMPILE_FLAGS$",
          Arrays.stream(includeDirs)
              .map(s -> quoter.quote("/I" + s))
              .collect(Collectors.joining(", ")));
      workspace.replaceFileContents(
          buildDefs,
          "$WINDOWS_LINK_FLAGS$",
          Arrays.stream(libDirs)
              .map(s -> quoter.quote("/LIBPATH:" + s))
              .collect(Collectors.joining(", ")));
    }
  }

  static void checkAssumptions() {
    assumeTrue("cl.exe should exist", Files.isExecutable(Paths.get(clExe)));

    assumeTrue("link.exe should exist", Files.isExecutable(Paths.get(linkExe)));

    assumeTrue("lib.exe should exist", Files.isExecutable(Paths.get(libExe)));

    for (String includeDir : includeDirs) {
      assumeTrue(
          String.format("include dir %s should exist", includeDir),
          Files.isDirectory(Paths.get(includeDir)));
    }

    for (String libDir : libDirs) {
      assumeTrue(
          String.format("lib dir %s should exist", libDir), Files.isDirectory(Paths.get(libDir)));
    }
  }
}
