/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.actions.lib.args;

/**
 * Build a "command line" from {@link CommandLineArgs}. This includes stringification and
 * modification of arguments as necessary (e.g. adding "./")
 */
public interface CommandLineBuilder {
  /**
   * @param commandLineArgs the command line args to stringify
   * @return An object with enough information information to invoke a command line program
   * @throws CommandLineArgException If one of the arguments returned from {@code commandLineArgs}
   *     was not of a valid type to be considered a command line argument. See {@link
   *     CommandLineArgStringifier}
   */
  CommandLine build(CommandLineArgs commandLineArgs) throws CommandLineArgException;
}
