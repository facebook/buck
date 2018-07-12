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

package com.facebook.buck.cli;

import com.facebook.buck.cli.parameter_extractors.CommandRunnerParameters;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.config.Config;
import java.nio.file.Path;

class CommandRunnerParametersImplementation implements CommandRunnerParameters {

  protected final CommandRunnerParams parameters;

  protected CommandRunnerParametersImplementation(CommandRunnerParams parameters) {
    this.parameters = parameters;
  }

  @Override
  public Console getConsole() {
    return parameters.getConsole();
  }

  @Override
  public DirtyPrintStreamDecorator getStdErr() {
    return parameters.getConsole().getStdErr();
  }

  @Override
  public DirtyPrintStreamDecorator getStdOut() {
    return parameters.getConsole().getStdOut();
  }

  @Override
  public Config getConfig() {
    return parameters.getBuckConfig().getConfig();
  }

  @Override
  public Parser getParser() {
    return parameters.getParser();
  }

  @Override
  public Path getPath() {
    return parameters.getCell().getRoot();
  }
}
