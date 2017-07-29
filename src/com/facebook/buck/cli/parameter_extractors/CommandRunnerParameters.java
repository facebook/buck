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

package com.facebook.buck.cli.parameter_extractors;

import com.facebook.buck.config.Config;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import java.nio.file.Path;

/**
 * Extract fields from {@code com.facebook.buck.cli.CommandRunnerParams} without a dependency on
 * {@code com.facebook.buck.cli}
 */
public interface CommandRunnerParameters {
  // Very minimal, so far!

  Console getConsole();

  DirtyPrintStreamDecorator getStdErr();

  DirtyPrintStreamDecorator getStdOut();

  Config getConfig();

  Parser getParser();

  Path getPath();
}
