/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Optional;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PythonBuckConfig {
  private static final Pattern PYTHON_VERSION_REGEX =
      Pattern.compile(".*?(\\wython \\d+\\.\\d+).*");

  private final BuckConfig delegate;

  public PythonBuckConfig(BuckConfig config) {
    this.delegate = config;
  }

  public PythonEnvironment getPythonEnvironment(ProcessExecutor processExecutor)
      throws InterruptedException {
    Path pythonPath = Paths.get(delegate.getPythonInterpreter());
    PythonVersion pythonVersion = getPythonVersion(processExecutor, pythonPath);
    return new PythonEnvironment(pythonPath, pythonVersion);
  }

  public Optional<Path> getPathToTestMain() {
    Optional <Path> testMain = delegate.getPath("python", "path_to_python_test_main");
     if (testMain.isPresent()) {
       return testMain;
    }

    // In some configs (particularly tests) it's possible that this variable will not be set.

    String rawPath = System.getProperty("buck.path_to_python_test_main");
    if (rawPath == null) {
      return Optional.absent();
    }

    return Optional.of(Paths.get(rawPath));
  }

  public Optional<Path> getPathToPex() {
    return delegate.getPath("python", "path_to_pex");
  }


  private static PythonVersion getPythonVersion(ProcessExecutor processExecutor, Path pythonPath)
      throws InterruptedException {
    try {
      ProcessExecutor.Result versionResult = processExecutor.execute(
          Runtime.getRuntime().exec(new String[]{pythonPath.toString(), "--version"}),
          EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_ERR),
          /* stdin */ Optional.<String>absent(),
          /* timeOutMs */ Optional.<Long>absent());
      return extractPythonVersion(pythonPath, versionResult);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Could not run \"%s --version\": %s",
          pythonPath,
          e.getMessage());
    }
  }

  @VisibleForTesting
  static PythonVersion extractPythonVersion(
      Path pythonPath,
      ProcessExecutor.Result versionResult) {
    if (versionResult.getExitCode() == 0) {
      String versionString = CharMatcher.WHITESPACE.trimFrom(versionResult.getStderr().get());
      Matcher matcher = PYTHON_VERSION_REGEX.matcher(versionString);
      if (!matcher.matches()) {
        throw new HumanReadableException(
            "`%s --version` returned an invalid version string %s",
            pythonPath,
            versionString);
      }
      return new PythonVersion(matcher.group(1));
    } else {
      throw new HumanReadableException(versionResult.getStderr().get());
    }
  }
}
