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

package com.facebook.buck.testutil;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.util.Escaper.Quoter;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * PlatformUtils exposes a consistent place to get potentially platform-specific configurations for
 * testing
 */
public abstract class PlatformUtils {
  private Quoter quoter;

  protected PlatformUtils(Quoter quoter) {
    this.quoter = quoter;
  }

  protected Optional<String> getClExe() {
    return Optional.empty();
  }

  protected Optional<String> getLinkExe() {
    return Optional.empty();
  }

  protected Optional<String> getLibExe() {
    return Optional.empty();
  }

  protected String[] getWindowsIncludeDirs() {
    return new String[] {};
  }

  protected String[] getWindowsLibDirs() {
    return new String[] {};
  }

  private String replacementForConfig(Optional<String> config) {
    if (config.isPresent()) {
      return quoter.quote(config.get());
    }
    return "";
  }

  /** Replaces any placeholders in the given workspace with appropriate platform-specific configs */
  public void setUpWorkspace(AbstractWorkspace workspace, String... cells) throws IOException {
    for (int i = -1; i < cells.length; i++) {
      String prefix = i == -1 ? "" : cells[i] + "/";
      String buckconfig = prefix + ".buckconfig";
      String buildDefs = prefix + "BUILD_DEFS";
      if (Files.exists(workspace.getPath(buckconfig))) {
        workspace.replaceFileContents(buckconfig, "$CL_EXE$", replacementForConfig(getClExe()));
        workspace.replaceFileContents(buckconfig, "$LIB_EXE$", replacementForConfig(getLibExe()));
        workspace.replaceFileContents(buckconfig, "$LINK_EXE$", replacementForConfig(getLinkExe()));
      }
      if (Files.exists(workspace.getPath(buildDefs))) {
        workspace.replaceFileContents(
            buildDefs,
            "$WINDOWS_COMPILE_FLAGS$",
            Arrays.stream(getWindowsIncludeDirs())
                .map(s -> quoter.quote("/I" + s))
                .collect(Collectors.joining(", ")));
        workspace.replaceFileContents(
            buildDefs,
            "$WINDOWS_LINK_FLAGS$",
            Arrays.stream(getWindowsLibDirs())
                .map(s -> quoter.quote("/LIBPATH:" + s))
                .collect(Collectors.joining(", ")));
      }
    }
  }

  /**
   * Make sure that files we believe should exist do, if we don't, we shouldn't continue the test
   * that likely relies on it.
   */
  public void checkAssumptions() {
    assumeTrue(
        "cl.exe should exist",
        !getClExe().isPresent() || Files.isExecutable(Paths.get(getClExe().get())));

    assumeTrue(
        "link.exe should exist",
        !getLinkExe().isPresent() || Files.isExecutable(Paths.get(getLinkExe().get())));

    assumeTrue(
        "lib.exe should exist",
        !getLibExe().isPresent() || Files.isExecutable(Paths.get(getLibExe().get())));

    for (String includeDir : getWindowsIncludeDirs()) {
      assumeTrue(
          String.format("include dir %s should exist", includeDir),
          Files.isDirectory(Paths.get(includeDir)));
    }

    for (String libDir : getWindowsLibDirs()) {
      assumeTrue(
          String.format("lib dir %s should exist", libDir), Files.isDirectory(Paths.get(libDir)));
    }
  }

  /** Returns the flavor of build rules for the given platform */
  public Optional<String> getFlavor() {
    return Optional.empty();
  }

  /**
   * Gets a base command builder for the given platform, where the utils adds in command necessary
   * to launch buck in the given environment
   */
  public abstract ImmutableList.Builder<String> getBuckCommandBuilder();

  /** Gets a base command builder for the given platform */
  public abstract ImmutableList.Builder<String> getCommandBuilder();

  /** Gets a PlatformUtils based on what Platform we're running tests in. */
  public static PlatformUtils getForPlatform() {
    if (Platform.detect() == Platform.WINDOWS) {
      return new WindowsUtils();
    }
    return new UnixUtils();
  }
}
