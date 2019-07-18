/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions.lib.args;

import com.facebook.buck.core.artifact.Artifact;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.CommandLineItem;

/**
 * Factory class that returns more efficient implementations of {@link CommandLineArgs} depending on
 * what type of arguments are available (e.g. a list of args may return a different concrete class
 * than a single arg)
 *
 * <p>This should be the public way to construct {@link CommandLineArgs} objects.
 */
public class CommandLineArgsFactory {

  // TODO(pjameson): Do all of this validation outside of the factory, and make from() the only
  //                 valid function. Also accept CommandLineArg in from(), and for things that do
  //                 not accept that (e.g. skylark's add(), add_all(), just reject there

  /**
   * Validates that an object is of a valid type to be a command line argument
   *
   * @param arg the command line argument, one of {@link String}, {@link Integer}, {@link
   *     CommandLineItem}, or {@link Artifact}
   * @return the original arg if it is a valid type
   * @throws CommandLineArgException if {@code arg} is not of a valid types
   */
  private static Object requireCorrectType(Object arg) {
    if (arg instanceof String
        || arg instanceof Integer
        || arg instanceof CommandLineItem
        || arg instanceof Artifact) {
      return arg;
    }
    throw new CommandLineArgException(arg);
  }

  /**
   * Create a {@link CommandLineArgs} instance for a list of arguments
   *
   * @param args the list of primitive command line args
   * @return A {@link CommandLineArgs} object for this collection of args
   * @throws CommandLineArgException if {@code args} contains an arg with an invalid type
   */
  public static CommandLineArgs from(ImmutableList<Object> args) throws CommandLineArgException {
    for (Object object : args) {
      requireCorrectType(object);
    }
    return new ListCommandLineArgs(args);
  }

  /**
   * @return a {@link CommandLineArgs} instance that is backed by a list of other {@link
   *     CommandLineArgs} objects
   */
  public static CommandLineArgs fromArgs(ImmutableList<CommandLineArgs> args) {
    return new AggregateCommandLineArgs(args);
  }

  /**
   * Create a {@link CommandLineArgs} instance for a list of {@link String} or {@link
   * CommandLineArgs}
   *
   * <p>This is generally helpful for accepting lists from users in their user defined rules where
   * they may be adding a single argument to another set of args passed in via a {@link
   * com.facebook.buck.core.rules.providers.ProviderInfo}
   *
   * @param args A list of {@link String} or {@link CommandLineArgs}
   * @return A {@link CommandLineArgs} object that returns a flattened list of args from {@code
   *     args}
   * @throws CommandLineArgException If one of the objects was not a string or {@link
   *     CommandLineArgs} instance
   */
  public static CommandLineArgs fromListOfStringsOrArgs(ImmutableList<Object> args)
      throws CommandLineArgException {
    if (args.stream().allMatch(arg -> arg instanceof String)) {
      return new ListCommandLineArgs(args);
    } else {
      return new AggregateCommandLineArgs(
          args.stream()
              .map(
                  arg -> {
                    if (arg instanceof String) {
                      return from(ImmutableList.of(arg));
                    } else if (arg instanceof CommandLineArgs) {
                      return (CommandLineArgs) arg;
                    } else {
                      throw new CommandLineArgException(arg);
                    }
                  })
              .collect(ImmutableList.toImmutableList()));
    }
  }
}
