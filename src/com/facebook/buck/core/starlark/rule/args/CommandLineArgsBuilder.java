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

package com.facebook.buck.core.starlark.rule.args;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;

/** Struct exposed to skylark to create {@link CommandLineArgs} instances. */
public class CommandLineArgsBuilder implements CommandLineArgsBuilderApi {

  ImmutableList.Builder<Object> argsBuilder = ImmutableList.builder();

  public CommandLineArgs build() {
    return CommandLineArgsFactory.from(argsBuilder.build());
  }

  /**
   * Validates that an object is of a valid type to be a command line argument
   *
   * @param arg the command line argument, one of {@link String}, {@link Integer}, {@link
   *     CommandLineItem}, or {@link Artifact}
   * @return the original arg if it is a valid type
   * @throws CommandLineArgException if {@code arg} is not of a valid type
   */
  private static Object requireCorrectType(Object arg) {
    // This is mostly adhering to the Bazel API. We could also allow
    // CommandLineArgsBuilder instances here and call .build() on them, but we just don't
    // right now
    if (arg instanceof String
        || arg instanceof Integer
        || arg instanceof CommandLineItem
        || arg instanceof Artifact
        || arg instanceof OutputArtifact
        || arg instanceof CommandLineArgs) {
      return arg;
    }
    throw new CommandLineArgException(arg);
  }

  @Override
  public CommandLineArgsBuilder add(
      Object argNameOrValue, Object value, String formatString, Location location)
      throws EvalException {
    formatString = CommandLineArgsFactory.validateFormatString(formatString);
    try {
      ImmutableList<Object> args;
      if (value == Runtime.UNBOUND) {
        args = ImmutableList.of(requireCorrectType(argNameOrValue));
      } else {
        args = ImmutableList.of(requireCorrectType(argNameOrValue), requireCorrectType(value));
      }
      argsBuilder.add(CommandLineArgsFactory.from(args, formatString));
    } catch (CommandLineArgException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }

    return this;
  }

  @Override
  public CommandLineArgsBuilder addAll(
      SkylarkList<?> values, String formatString, Location location) throws EvalException {

    try {
      for (Object value : values) {
        requireCorrectType(value);
      }
      argsBuilder.add(
          CommandLineArgsFactory.from(
              BuckSkylarkTypes.toJavaList(values, Object.class, "object class"), formatString));
    } catch (CommandLineArgException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
    return this;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<args>");
  }
}
