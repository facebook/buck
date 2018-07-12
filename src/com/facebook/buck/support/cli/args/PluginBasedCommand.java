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

import com.google.common.collect.ImmutableList;
import java.io.PrintStream;

/**
 * An abstract class that provides basic capabilities for commands that use subcommands loaded from
 * plugins.
 *
 * <p>Implementations of this class will have logic to control common logic of a particular
 * plugin-based command. For example, {@code buck project} command will have an implementation that
 * implements this class and adds some common domain-independent logic to route a particular
 * invocation to some implementation loaded from plugins.
 *
 * <p>Typical example of usage:
 *
 * <pre>
 *   class ExamplePluginBasedCommand extends PluginBasedCommand {
 *    {@literal @}PluginBasedSubCommands(commandClass = ExamplePluginBasedSubCommand.class)
 *    {@literal @}SuppressFieldNotInitialized
 *     private ImmutableList<ExamplePluginBasedSubCommand> modes;
 *
 *    {@literal @}Override
 *     protected ImmutableList<? extends PluginBasedSubCommand> getSubCommands() {
 *       return modes;
 *     }
 *
 *    {@literal @}Override
 *     protected String getTypeOptionName() {
 *       return "--mode";
 *     }
 *   }
 * </pre>
 *
 * <p>Key points:
 *
 * <ul>
 *   <li>Plugin subcommands have to implement {@code ExamplePluginBasedSubCommand}.
 *   <li>{@link #getTypeOptionName} is used to control which option is used to select a subcommand.
 *       The logic in this class uses that method for help screen only. The particular
 *       implementations need to use the option with that name to select a particular subcommand.
 * </ul>
 */
public interface PluginBasedCommand {

  /** @return all subcommands known to this command. */
  ImmutableList<? extends PluginBasedSubCommand> getSubCommands();

  /** @return the name of the option that is used to control which subcommand is invoked. */
  String getTypeOptionName();

  /** @return a pharse that describes the purpose of this command. */
  String getShortDescription();

  /** Prints the usage to the provided stream. */
  default void printUsage(PrintStream stream) {
    new PluginBasedCommandHelpPrinter().printUsage(this, stream);
  }
}
