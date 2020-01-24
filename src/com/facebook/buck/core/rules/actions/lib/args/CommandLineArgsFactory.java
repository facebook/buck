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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.CommandLineItem;
import org.apache.commons.lang.StringUtils;

/**
 * Factory class that returns more efficient implementations of {@link CommandLineArgs} depending on
 * what type of arguments are available (e.g. a list of args may return a different concrete class
 * than a single arg)
 *
 * <p>This should be the public way to construct {@link CommandLineArgs} objects.
 */
public class CommandLineArgsFactory {
  /**
   * Throws an exception if the {@code formatString} is not a valid stringification format string
   */
  public static String validateFormatString(String formatString) throws CommandLineArgException {
    if (formatString.equals(CommandLineArgs.DEFAULT_FORMAT_STRING)) {
      return CommandLineArgs.DEFAULT_FORMAT_STRING;
    }
    if (StringUtils.countMatches(formatString, "%s") == 0) {
      throw new CommandLineArgException(
          "format string '%s' must be a format string with one or more occurrences of %%%%s",
          formatString.replace("%s", "%%s"));
    }
    return formatString;
  }

  /**
   * Create a {@link CommandLineArgs} instance for a list of arguments
   *
   * @param args the list of primitive command line args
   * @return A {@link CommandLineArgs} object for this collection of args
   * @throws CommandLineArgException if {@code args} contains an arg with an invalid type
   */
  public static CommandLineArgs from(ImmutableList<Object> args) throws CommandLineArgException {
    return from(args, CommandLineArgs.DEFAULT_FORMAT_STRING);
  }

  /**
   * Create a {@link CommandLineArgs} instance for a list of arguments
   *
   * @param args the list of primitive command line args
   * @param formatString the format string to apply after stringifying arguments
   * @return A {@link CommandLineArgs} object for this collection of args
   * @throws CommandLineArgException if {@code args} contains an arg with an invalid type
   */
  @SuppressWarnings("unchecked")
  public static CommandLineArgs from(ImmutableList<Object> args, String formatString)
      throws CommandLineArgException {
    boolean foundCommandLineArg = false;
    boolean foundNonCommandLineArg = false;

    String validatedFormatString = validateFormatString(formatString);

    // Yes, this means we loop over args.size() more than necessary sometimes. However, it also
    // allows us to do some quick conversions below. Worst case is 2N iterations, but best case is
    // just N for type checking, then passing the list into the right constructor, with no extra
    // allocations/copies/conversions
    for (Object arg : args) {
      if (arg instanceof String
          || arg instanceof Integer
          || arg instanceof CommandLineItem
          || arg instanceof OutputArtifact) {
        foundNonCommandLineArg = true;
      } else if (arg instanceof Artifact) {
        foundNonCommandLineArg = true;
        Artifact artifact = (Artifact) arg;
        if (!artifact.isBound()) {
          throw new CommandLineArgException(
              "Artifact %s was not used as the output to an action. Either make it the output of "
                  + "an action first to specify that it should be an input, or call "
                  + "`.as_output()` on it when adding it to indicate it should be an output.",
              arg);
        }
      } else if (arg instanceof CommandLineArgs) {
        foundCommandLineArg = true;
      } else {
        throw new CommandLineArgException(arg);
      }
    }

    if (foundCommandLineArg) {
      if (foundNonCommandLineArg) {
        ImmutableList.Builder<CommandLineArgs> builder =
            ImmutableList.builderWithExpectedSize(args.size());
        args.stream()
            .map(
                arg -> {
                  if (arg instanceof CommandLineArgs) {
                    return (CommandLineArgs) arg;
                  } else {
                    return new ListCommandLineArgs(ImmutableList.of(arg), validatedFormatString);
                  }
                })
            .forEach(builder::add);
        return new AggregateCommandLineArgs(builder.build());
      } else {
        // We only have CommandLineArgs objects, so we should be fine to cast this to a more
        // specific ImmutableList type. If this were a mutable list, we wouldn't be able to do this
        ImmutableList<CommandLineArgs> commandLineArgs =
            (ImmutableList<CommandLineArgs>) (ImmutableList<?>) args;
        return new AggregateCommandLineArgs(commandLineArgs);
      }
    }

    return new ListCommandLineArgs(args, validatedFormatString);
  }
}
