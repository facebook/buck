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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class PythonInterpreterFromConfig implements PythonInterpreter {

  // Prefer "python2" where available (Linux), but fall back to "python" (Mac).
  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private final PythonBuckConfig pythonBuckConfig;
  private final ExecutableFinder executableFinder;

  public PythonInterpreterFromConfig(
      PythonBuckConfig pythonBuckConfig, ExecutableFinder executableFinder) {
    this.pythonBuckConfig = pythonBuckConfig;
    this.executableFinder = executableFinder;
  }

  @Override
  public Path getPythonInterpreterPath(String section) {
    return getPythonInterpreter(section);
  }

  @Override
  public Path getPythonInterpreterPath() {
    return getPythonInterpreter(pythonBuckConfig.getDefaultSection());
  }

  private Path findInterpreter(ImmutableList<String> interpreterNames) {
    Preconditions.checkArgument(!interpreterNames.isEmpty());
    for (String interpreterName : interpreterNames) {
      Optional<Path> python =
          executableFinder.getOptionalExecutable(
              Paths.get(interpreterName), pythonBuckConfig.getDelegate().getEnvironment());
      if (python.isPresent()) {
        return python.get().toAbsolutePath();
      }
    }
    throw new HumanReadableException(
        "No python interpreter found (searched %s).", Joiner.on(", ").join(interpreterNames));
  }

  /**
   * Returns the path to python interpreter. If python is specified in 'interpreter' key of the
   * 'python' section that is used and an error reported if invalid.
   *
   * @return The found python interpreter.
   */
  private Path getPythonInterpreter(String section) {
    Optional<String> pathToInterpreter = pythonBuckConfig.getInterpreter(section);
    if (!pathToInterpreter.isPresent()) {
      return findInterpreter(PYTHON_INTERPRETER_NAMES);
    }
    Path configPath = Paths.get(pathToInterpreter.get());
    if (!configPath.isAbsolute()) {
      return findInterpreter(ImmutableList.of(pathToInterpreter.get()));
    }
    return configPath;
  }
}
