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

package com.facebook.buck.testutil;

import com.facebook.buck.util.Escaper.Quoter;
import com.google.common.collect.ImmutableList;
import java.nio.file.FileSystems;
import java.util.Optional;

/** An implementation of {@link PlatformUtils} for Windows platforms */
public class WindowsUtils extends PlatformUtils {
  private static final String PROGRAM_FILES_DIR = "C:\\Program Files (x86)";

  private static final String VISUAL_STUDIO_DIR =
      PROGRAM_FILES_DIR + "\\Microsoft Visual Studio 14.0\\VC";

  private static final String WINDOWS_KITS_DIR = PROGRAM_FILES_DIR + "\\Windows Kits\\10";

  private static final String CL_EXE = VISUAL_STUDIO_DIR + "\\bin\\amd64\\cl.exe";

  private static final String LINK_EXE = VISUAL_STUDIO_DIR + "\\bin\\amd64\\link.exe";

  private static final String LIB_EXE = VISUAL_STUDIO_DIR + "\\bin\\amd64\\lib.exe";

  private static final String[] WINDOWS_INCLUDE_DIRS =
      new String[] {
        VISUAL_STUDIO_DIR + "\\include",
        WINDOWS_KITS_DIR + "\\Include\\10.0.10586.0\\ucrt",
        WINDOWS_KITS_DIR + "\\Include\\10.0.10586.0\\um",
        WINDOWS_KITS_DIR + "\\Include\\10.0.10586.0\\shared"
      };

  private static final String[] WINDOWS_LIB_DIRS =
      new String[] {
        VISUAL_STUDIO_DIR + "\\LIB\\amd64",
        WINDOWS_KITS_DIR + "\\lib\\10.0.10586.0\\ucrt\\x64",
        WINDOWS_KITS_DIR + "\\lib\\10.0.10586.0\\um\\x64",
      };

  private static final String VCVARSALLBAT = VISUAL_STUDIO_DIR + "\\vcvarsall.bat";

  private static final String BUCK_EXE =
      FileSystems.getDefault().getPath("bin", "buck").toAbsolutePath().toString();

  @Override
  protected Optional<String> getClExe() {
    return Optional.of(CL_EXE);
  }

  @Override
  protected Optional<String> getLinkExe() {
    return Optional.of(LINK_EXE);
  }

  @Override
  protected Optional<String> getLibExe() {
    return Optional.of(LIB_EXE);
  }

  @Override
  protected String[] getWindowsIncludeDirs() {
    return WINDOWS_INCLUDE_DIRS;
  }

  @Override
  protected String[] getWindowsLibDirs() {
    return WINDOWS_LIB_DIRS;
  }

  public WindowsUtils() {
    super(Quoter.DOUBLE_WINDOWS_JAVAC);
  }

  /** Returns the flavor of build rules for Windows */
  @Override
  public Optional<String> getFlavor() {
    return Optional.of("windows-x86_64");
  }

  /** Returns a buck command builder for a unix platform, which runs programs through cmd */
  @Override
  public ImmutableList.Builder<String> getCommandBuilder() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.<String>builder();
    commandBuilder.add("cmd").add("/c");
    return commandBuilder;
  }

  /**
   * Returns a buck command builder for a unix platform, which runs buck through cmd.exe running
   * bin/buck
   */
  @Override
  public ImmutableList.Builder<String> getBuckCommandBuilder() {
    return getCommandBuilder().add(BUCK_EXE);
  }

  /** Gets the location of vcvarsallbat on the windows platform */
  public String getVcvarsallbat() {
    return VCVARSALLBAT;
  }
}
