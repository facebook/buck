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
}
