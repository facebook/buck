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
import com.facebook.buck.cxx.VersionedTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PythonBuckConfig {

  private static final String SECTION = "python";

  private static final Pattern PYTHON_VERSION_REGEX =
      Pattern.compile(".*?(\\wy(thon|run) \\d+\\.\\d+).*");

  // Prefer "python2" where available (Linux), but fall back to "python" (Mac).
  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(
          System.getProperty(
              "buck.path_to_pex",
              "src/com/facebook/buck/python/pex.py"))
          .toAbsolutePath();

  private static final Path DEFAULT_PATH_TO_TEST_MAIN =
      Paths.get(
          System.getProperty(
              "buck.path_to_python_test_main",
              "src/com/facebook/buck/python/__test_main__.py"))
          .toAbsolutePath();

  private final BuckConfig delegate;
  private final ExecutableFinder exeFinder;

  public PythonBuckConfig(BuckConfig config, ExecutableFinder exeFinder) {
    this.delegate = config;
    this.exeFinder = exeFinder;
  }

  /**
   * @return true if file is executable and not a directory.
   */
  private boolean isExecutableFile(File file) {
    return file.canExecute() && !file.isDirectory();
  }

  /**
   * Returns the path to python interpreter. If python is specified in 'interpreter' key
   * of the 'python' section that is used and an error reported if invalid.
   * @return The found python interpreter.
   */
  public String getPythonInterpreter() {
    Optional<String> configPath = delegate.getValue(SECTION, "interpreter");
    ImmutableList<String> pythonInterpreterNames = PYTHON_INTERPRETER_NAMES;
    if (configPath.isPresent()) {
      // Python path in config. Use it or report error if invalid.
      File python = new File(configPath.get());
      if (isExecutableFile(python)) {
        return python.getAbsolutePath();
      }
      if (python.isAbsolute()) {
        throw new HumanReadableException("Not a python executable: " + configPath.get());
      }
      pythonInterpreterNames = ImmutableList.of(configPath.get());
    }

    for (String interpreterName : pythonInterpreterNames) {
      Optional<Path> python = exeFinder.getOptionalExecutable(
          Paths.get(interpreterName),
          delegate.getEnvironment());
      if (python.isPresent()) {
        return python.get().toAbsolutePath().toString();
      }
    }

    if (configPath.isPresent()) {
      throw new HumanReadableException("Not a python executable: " + configPath.get());
    } else {
      throw new HumanReadableException("No python2 or python found.");
    }
  }

  public PythonEnvironment getPythonEnvironment(ProcessExecutor processExecutor)
      throws InterruptedException {
    Path pythonPath = Paths.get(getPythonInterpreter());
    PythonVersion pythonVersion = getPythonVersion(processExecutor, pythonPath);
    return new PythonEnvironment(pythonPath, pythonVersion);
  }

  public Path getPathToTestMain() {
    return delegate.getPath(SECTION, "path_to_python_test_main").or(DEFAULT_PATH_TO_TEST_MAIN);
  }

  public Tool getPexTool(BuildRuleResolver resolver) {
    Optional<Tool> executable = delegate.getTool(SECTION, "path_to_pex", resolver);
    if (executable.isPresent()) {
      return executable.get();
    }
    return new VersionedTool(
        Paths.get(getPythonInterpreter()),
        ImmutableList.of(DEFAULT_PATH_TO_PEX.toString()),
        "pex",
        BuckVersion.getVersion());
  }

  public Path getPathToPexExecuter() {
    Optional<Path> path = delegate.getPath(SECTION, "path_to_pex_executer");
    if (!path.isPresent()) {
      return Paths.get(getPythonInterpreter());
    }
    if (!isExecutableFile(path.get().toFile())) {
      throw new HumanReadableException(
          "%s is not executable (set in python.path_to_pex_executer in your config",
          path.get().toString());
    }
    return path.get();
  }

  public String getPexExtension() {
    return delegate.getValue(SECTION, "pex_extension").or(".pex");
  }

  private static PythonVersion getPythonVersion(ProcessExecutor processExecutor, Path pythonPath)
      throws InterruptedException {
    try {
      ProcessExecutor.Result versionResult = processExecutor.launchAndExecute(
          ProcessExecutorParams.builder().addCommand(pythonPath.toString(), "-V").build(),
          EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_ERR),
          /* stdin */ Optional.<String>absent(),
          /* timeOutMs */ Optional.<Long>absent(),
          /* timeoutHandler */ Optional.<Function<Process, Void>>absent());
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
      String versionString = CharMatcher.WHITESPACE.trimFrom(
          CharMatcher.WHITESPACE.trimFrom(versionResult.getStderr().get()) +
          CharMatcher.WHITESPACE.trimFrom(versionResult.getStdout().get())
              .replaceAll("\u001B\\[[;\\d]*m", ""));
      Matcher matcher = PYTHON_VERSION_REGEX.matcher(versionString);
      if (!matcher.matches()) {
        throw new HumanReadableException(
            "`%s -V` returned an invalid version string %s",
            pythonPath,
            versionString);
      }
      return PythonVersion.of(matcher.group(1));
    } else {
      throw new HumanReadableException(versionResult.getStderr().get());
    }
  }

  public PackageStyle getPackageStyle() {
    return delegate.getEnum(SECTION, "package_style", PackageStyle.class)
        .or(PackageStyle.STANDALONE);
  }

  public enum PackageStyle {
    STANDALONE,
    INPLACE,
  }

}
