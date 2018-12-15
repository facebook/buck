/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.environment.PlatformType;
import com.facebook.buck.util.string.MoreStrings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Helper class to setup cxx toolchains in a platform-independent way. */
public class CxxToolchainUtilsForTests {

  private static final Path WINDOWS_CXX_TOOLCHAIN_LOCATION =
      Paths.get("C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/bin/amd64");

  private CxxToolchainUtilsForTests() {}

  private static void assumeWindowsCxxToolchainIsPresent() {
    assumeTrue(Files.exists(WINDOWS_CXX_TOOLCHAIN_LOCATION));
  }

  public static void configureCxxToolchains(ProjectWorkspace workspace) throws IOException {
    boolean isWindowsOs = Platform.detect().getType() == PlatformType.WINDOWS;
    if (isWindowsOs) {
      assumeWindowsCxxToolchainIsPresent();
    }
    String config = isWindowsOs ? getWindowsCxxConfig() : getPosixConfig();
    workspace.writeContentsToPath(config, ".buckconfig");
  }

  private static String getWindowsCxxConfig() {
    Path cl = WINDOWS_CXX_TOOLCHAIN_LOCATION.resolve("cl.exe");
    Path link = WINDOWS_CXX_TOOLCHAIN_LOCATION.resolve("link.exe");
    Path lib = WINDOWS_CXX_TOOLCHAIN_LOCATION.resolve("lib.exe");
    return String.format(
        MoreStrings.linesToText(
            "[cxx]",
            "  cc=\"%1$s\"",
            "  cc_type=windows",
            "  cpp=\"%1$s\"",
            "  cpp_type=windows",
            "  cxx=\"%1$s\"",
            "  cxx_type=windows",
            "  cxxpp=\"%1$s\"",
            "  cxxpp_type=windows",
            "  ld=\"%2$s\"",
            "  linker_platform=windows",
            "  ar=\"%3$s\"",
            "  archiver_platform=windows",
            "  ranlib=\"%3$s\""),
        MorePaths.pathWithUnixSeparators(cl),
        MorePaths.pathWithUnixSeparators(link),
        MorePaths.pathWithUnixSeparators(lib));
  }

  private static String getPosixConfig() {
    return MoreStrings.linesToText(
        "[cxx]",
        "  cppflags = -Wall -Werror",
        "  cxxppflags = -Wall -Werror",
        "  cflags = -Wall -Werror",
        "  cxxflags = -Wall -Werror");
  }
}
