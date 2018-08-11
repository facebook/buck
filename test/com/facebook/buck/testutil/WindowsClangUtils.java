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

import com.google.common.collect.ImmutableList;
import java.nio.file.FileSystems;
import java.util.Optional;

/** An implementation of {@link PlatformUtils} for Windows platforms for running Clang tests. */
public class WindowsClangUtils extends PlatformUtils {

  private static final String[] VS_TOOLCHAIN_DIRS =
      new String[] {"C:/tools/toolchains/vs2017_15.5", "C:/tools/toolchains/LLVM"};

  private static final String BUCK_EXE =
      FileSystems.getDefault().getPath("bin", "buck").toAbsolutePath().toString();

  @Override
  public String[] getVsToolchainDirs() {
    return VS_TOOLCHAIN_DIRS;
  }

  public WindowsClangUtils() {}

  /** Returns the flavor of build rules for Windows */
  @Override
  public Optional<String> getFlavor() {
    return Optional.of("windows-x86_64");
  }

  /** Returns a buck command builder for a unix platform, which runs programs through cmd */
  @Override
  public ImmutableList.Builder<String> getCommandBuilder() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
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
}
