/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.Iterator;
import java.util.List;

public class CompositeCommand implements Command, Iterable<Command> {

  private final ImmutableList<Command> commands;

  public CompositeCommand(List<? extends Command> commands) {
    Preconditions.checkNotNull(commands);
    Preconditions.checkArgument(!commands.isEmpty(), "Must have at least one command");
    this.commands = ImmutableList.copyOf(commands);
  }

  @Override
  public int execute(ExecutionContext context) {
    for (Command command : commands) {
      int exitCode = command.execute(context);
      if (exitCode != 0) {
        return exitCode;
      }
    }
    return 0;
  }

  @Override
  public String getDescription(final ExecutionContext context) {
    return Joiner.on(" && ").join(Iterables.transform(commands,
        new Function<Command, String>() {
          @Override
          public String apply(Command command) {
            return command.getDescription(context);
          }
    }));
  }

  @Override
  public String getShortName(final ExecutionContext context) {
    return Joiner.on(" && ").join(Iterables.transform(commands,
        new Function<Command, String>() {
      @Override
      public String apply(Command command) {
        return command.getShortName(context);
      }
    }));
  }

  @Override
  public Iterator<Command> iterator() {
    return commands.iterator();
  }

}
