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

package com.facebook.buck.features.python.toolchain.impl;

import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PythonVersionFactory {

  private static final Pattern VERSION_RE = Pattern.compile("(?<name>[^ ]*) (?<version>[^ ]*)");

  /** @return parse a {@link PythonVersion} from a version string (e.g. "CPython 2.7"). */
  public static PythonVersion fromString(String versionStr) {
    Matcher matcher = VERSION_RE.matcher(versionStr);
    if (!matcher.matches()) {
      throw new HumanReadableException(
          "invalid version string \"%s\", expected `<name> <version>` (e.g. `CPython 2.7`)",
          versionStr);
    }
    return PythonVersion.of(matcher.group("name"), matcher.group("version"));
  }

  /** @return a {@link PythonVersion} extracted from running the given interpreter. */
  public static PythonVersion fromInterpreter(ProcessExecutor processExecutor, Path pythonPath)
      throws InterruptedException {
    try {
      // Taken from pex's interpreter.py.
      String versionId =
          "import sys\n"
              + "\n"
              + "if hasattr(sys, 'pypy_version_info'):\n"
              + "  subversion = 'PyPy'\n"
              + "elif sys.platform.startswith('java'):\n"
              + "  subversion = 'Jython'\n"
              + "else:\n"
              + "  subversion = 'CPython'\n"
              + "\n"
              + "print('%s %s.%s' % (subversion, sys.version_info[0], "
              + "sys.version_info[1]))\n";

      ProcessExecutor.Result versionResult =
          processExecutor.launchAndExecute(
              ProcessExecutorParams.builder().addCommand(pythonPath.toString(), "-").build(),
              EnumSet.of(
                  ProcessExecutor.Option.EXPECTING_STD_OUT,
                  ProcessExecutor.Option.EXPECTING_STD_ERR),
              Optional.of(versionId),
              /* timeOutMs */ Optional.empty(),
              /* timeoutHandler */ Optional.empty());
      return extractPythonVersion(pythonPath, versionResult);
    } catch (IOException e) {
      throw new HumanReadableException(
          e, "Could not run \"%s - < [code]\": %s", pythonPath, e.getMessage());
    }
  }

  @VisibleForTesting
  static PythonVersion extractPythonVersion(Path pythonPath, ProcessExecutor.Result versionResult) {
    if (versionResult.getExitCode() != 0) {
      throw new HumanReadableException(versionResult.getStderr().get());
    }
    String versionString =
        CharMatcher.whitespace()
            .trimFrom(
                CharMatcher.whitespace().trimFrom(versionResult.getStderr().get())
                    + CharMatcher.whitespace()
                        .trimFrom(versionResult.getStdout().get())
                        .replaceAll("\u001B\\[[;\\d]*m", ""));
    String[] versionLines = versionString.split("\\r?\\n");
    try {
      return fromString(versionLines[0]);
    } catch (HumanReadableException e) {
      throw new HumanReadableException(e, "`%s - < [code]`: %s", pythonPath, e.getMessage());
    }
  }
}
