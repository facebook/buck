/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxToolProvider.Type;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilities for inferring the {@link Type} of a cxx tool. */
public class CxxToolTypeInferer {
  private static final Logger LOG = Logger.get(CxxToolProvider.class);
  private static final Pattern CLANG_VERSION_PATTERN =
      Pattern.compile(
          "\\s*("
              + // Ignore leading whitespace.
              "clang version [.0-9]*(\\s*\\(.*\\))?"
              + // Format used by opensource Clang.
              "|"
              + "Apple LLVM version [.0-9]*\\s*\\(clang-[.0-9]*\\)"
              + // Format used by Apple's clang.
              ")\\s*"); // Ignore trailing whitespace.

  /**
   * Invokes the tool with `--version` and parses the output to determine the type of the compiler.
   */
  public static Type getTypeFromPath(PathSourcePath path) {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand(path.getFilesystem().resolve(path.getRelativePath()).toString())
            .addCommand("--version")
            .build();
    ProcessExecutor.Result result;
    try {
      ProcessExecutor processExecutor = new DefaultProcessExecutor(Console.createNullConsole());
      result =
          processExecutor.launchAndExecute(
              params,
              EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }

    if (result.getExitCode() != 0) {
      String commandString = params.getCommand().toString();
      String message = result.getMessageForUnexpectedResult(commandString);
      LOG.error(message);
      throw new RuntimeException(message);
    }

    String stdout = result.getStdout().orElse("");
    Iterable<String> lines = Splitter.on(CharMatcher.anyOf("\r\n")).split(stdout);
    LOG.debug("Output of %s: %s", params.getCommand(), lines);
    return getTypeFromVersionOutput(lines);
  }

  /** Checks if the output looks like `clang --version`'s output. */
  @VisibleForTesting
  protected static Type getTypeFromVersionOutput(Iterable<String> lines) {
    for (String line : lines) {
      Matcher matcher = CLANG_VERSION_PATTERN.matcher(line);
      if (matcher.matches()) {
        return Type.CLANG;
      }
    }
    return Type.GCC;
  }
}
