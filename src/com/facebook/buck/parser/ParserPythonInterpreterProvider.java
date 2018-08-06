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

package com.facebook.buck.parser;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class ParserPythonInterpreterProvider {

  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private final ParserConfig parserConfig;
  private final ExecutableFinder executableFinder;

  public ParserPythonInterpreterProvider(BuckConfig buckConfig, ExecutableFinder executableFinder) {
    this(buckConfig.getView(ParserConfig.class), executableFinder);
  }

  public ParserPythonInterpreterProvider(
      ParserConfig parserConfig, ExecutableFinder executableFinder) {
    this.parserConfig = parserConfig;
    this.executableFinder = executableFinder;
  }

  /**
   * Returns the path to python interpreter. If python is specified in the 'python_interpreter' key
   * of the 'parser' section that is used and an error reported if invalid.
   *
   * <p>If none has been specified, consult the PythonBuckConfig for an interpreter.
   *
   * @return The found python interpreter.
   */
  public String getOrFail() {
    Path path = getPythonInterpreter(parserConfig.getParserPythonInterpreterPath());
    if (!(Files.isExecutable(path) && !Files.isDirectory(path))) {
      throw new HumanReadableException("Not a python executable: " + path);
    }
    return path.toString();
  }

  private Path findInterpreter(ImmutableList<String> interpreterNames) {
    Preconditions.checkArgument(!interpreterNames.isEmpty());
    for (String interpreterName : interpreterNames) {
      Optional<Path> python =
          executableFinder.getOptionalExecutable(
              Paths.get(interpreterName), parserConfig.getDelegate().getEnvironment());
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
  private Path getPythonInterpreter(Optional<String> interpreterPath) {
    if (!interpreterPath.isPresent()) {
      return findInterpreter(PYTHON_INTERPRETER_NAMES);
    }
    Path configPath = Paths.get(interpreterPath.get());
    if (!configPath.isAbsolute()) {
      return findInterpreter(ImmutableList.of(interpreterPath.get()));
    }
    return configPath;
  }
}
