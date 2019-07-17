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
package com.facebook.buck.core.starlark.rule.args;

import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;

/** Struct exposed to skylark to create {@link CommandLineArgs} instances. */
public class CommandLineArgsBuilder implements CommandLineArgsBuilderApi {

  ImmutableList.Builder<CommandLineArgs> argsBuilder = ImmutableList.builder();

  public CommandLineArgs build() {
    return CommandLineArgsFactory.fromArgs(argsBuilder.build());
  }

  @Override
  public CommandLineArgsBuilder add(Object argNameOrValue, Object value, Location location)
      throws EvalException {

    try {
      ImmutableList<Object> args;
      if (value == Runtime.UNBOUND) {
        args = ImmutableList.of(argNameOrValue);

      } else {
        args = ImmutableList.of(argNameOrValue, value);
      }
      argsBuilder.add(CommandLineArgsFactory.from(args));
    } catch (CommandLineArgException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }

    return this;
  }

  @Override
  public CommandLineArgsBuilder addAll(SkylarkList<Object> values, Location location)
      throws EvalException {

    try {
      argsBuilder.add(CommandLineArgsFactory.from(values.getImmutableList()));
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
