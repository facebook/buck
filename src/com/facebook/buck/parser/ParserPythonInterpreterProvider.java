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

import com.facebook.buck.python.toolchain.PythonInterpreter;
import com.facebook.buck.util.HumanReadableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

class ParserPythonInterpreterProvider {

  private final PythonInterpreter pythonInterpreter;
  private final ParserConfig parserConfig;

  ParserPythonInterpreterProvider(PythonInterpreter pythonInterpreter, ParserConfig parserConfig) {
    this.pythonInterpreter = pythonInterpreter;
    this.parserConfig = parserConfig;
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
    Path path =
        parserConfig
            .getParserPythonInterpreterPath()
            .map(c -> pythonInterpreter.getPythonInterpreterPath(Optional.of(c)))
            // Fall back to the Python section configuration
            .orElseGet(pythonInterpreter::getPythonInterpreterPath);
    if (!(Files.isExecutable(path) && !Files.isDirectory(path))) {
      throw new HumanReadableException("Not a python executable: " + path);
    }
    return path.toString();
  }
}
