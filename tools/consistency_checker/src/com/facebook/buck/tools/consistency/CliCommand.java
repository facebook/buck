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

package com.facebook.buck.tools.consistency;

import org.kohsuke.args4j.Option;

/** Common super class for the main CLI args class, and all subcommands */
public class CliCommand {
  private final String description;

  /**
   * Creates an instance of {@link CliCommand}
   *
   * @param description A description of the command/subcommand
   */
  public CliCommand(String description) {
    this.description = description;
  }

  @Option(name = "--help", usage = "Show this help")
  boolean showHelp = false;

  String getDescription() {
    return description;
  }
}
