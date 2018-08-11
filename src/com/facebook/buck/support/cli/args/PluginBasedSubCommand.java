/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.support.cli.args;

/** A common interface that needs to be implemented by subcommands loaded from plugins. */
public interface PluginBasedSubCommand {
  /**
   * The value of the option that identifies a subcommand from a particular plugin.
   *
   * <p>For example, for {@code buck project} that would be {@code intellij} or {@code xcode}.
   */
  String getOptionValue();

  /** @return a phrase describing the purpose of this subcommand when displaying the help screen. */
  String getShortDescription();
}
