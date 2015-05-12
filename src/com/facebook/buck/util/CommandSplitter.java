/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.collect.ImmutableList;

import java.util.Iterator;
import java.util.List;

/**
 * Splits an argument list into a list of command invocations whose total length will not exceed
 * the specified limit if possible. If that is not possible, all arguments that exceed the limit
 * will be used in their own invocation separate from all other arguments to minimize the maximal
 * command length.
 */
public class CommandSplitter {

  private final int desiredLimit;
  private final ImmutableList<String> commandPrefix;
  private final int commandPrefixLength;

  private static final int DEFAULT_DESIRED_LIMIT = 32 * 1024;

  public CommandSplitter(
      List<String> commandPrefix,
      int desiredLimit) {
    this.desiredLimit = desiredLimit;
    this.commandPrefix = ImmutableList.copyOf(commandPrefix);
    int commandPrefixLength = 0;
    for (String argument : commandPrefix) {
      commandPrefixLength += argument.length() + 1; // +1 for separator
    }
    this.commandPrefixLength = commandPrefixLength;
  }

  public CommandSplitter(List<String> commandPrefix) {
    this(commandPrefix, DEFAULT_DESIRED_LIMIT);
  }

  public ImmutableList<ImmutableList<String>> getCommandsForArguments(Iterable<String> arguments) {
    Iterator<String> argumentIterator = arguments.iterator();
    if (!argumentIterator.hasNext()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<ImmutableList<String>> commands = ImmutableList.builder();
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    String firstArgument = argumentIterator.next();
    commandBuilder
        .addAll(commandPrefix)
        .add(firstArgument);
    int commandLength = commandPrefixLength + firstArgument.length() + 1; // +1 for separator

    while (argumentIterator.hasNext()) {
      // Loop invariant: commandBuilder contains at least one argument after the command prefix.
      String argument = argumentIterator.next();
      int updatedCommandLength = commandLength + argument.length() + 1; // +1 for separator
      if (updatedCommandLength > desiredLimit) {
        // Put the current command into the output list and start building a new command
        commands.add(commandBuilder.build());
        commandBuilder = ImmutableList.<String>builder().addAll(commandPrefix);
        commandLength = commandPrefixLength;
      }
      commandBuilder.add(argument);
      commandLength += argument.length() + 1; // +1 for separator
    }

    commands.add(commandBuilder.build());

    return commands.build();
  }

}
