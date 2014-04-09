/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Events tracking the start and stop of a buck command.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class CommandEvent extends AbstractBuckEvent {
  private final String commandName;
  private final ImmutableList<String> args;
  private final boolean isDaemon;

  /**
   * @param commandName The name of the Buck subcommand, such as {@code build} or {@code test}.
   * @param args The arguments passed to the subcommand. These are often build targets.
   * @param isDaemon Whether the daemon was in use.
   */
  private CommandEvent(String commandName, ImmutableList<String> args, boolean isDaemon) {
    this.commandName = Preconditions.checkNotNull(commandName);
    this.args = Preconditions.checkNotNull(args);
    this.isDaemon = isDaemon;
  }

  public String getCommandName() {
    return commandName;
  }

  /** @return Arguments passed to {@link #getCommandName()}. */
  public ImmutableList<String> getArgs() {
    return args;
  }

  public boolean isDaemon() {
    return isDaemon;
  }

  @Override
  protected String getValueString() {
    return String.format("%s, isDaemon: %b", commandName, isDaemon);
  }

  @Override
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof CommandEvent)) {
      return false;
    }

    CommandEvent that = (CommandEvent) event;

    return Objects.equal(getCommandName(), that.getCommandName()) &&
        Objects.equal(getArgs(), that.getArgs()) &&
        Objects.equal(isDaemon(), that.isDaemon());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getCommandName(), getArgs(), isDaemon());
  }

  public static Started started(String commandName, ImmutableList<String> args, boolean isDaemon) {
    return new Started(commandName, args, isDaemon);
  }

  public static Finished finished(String commandName,
      ImmutableList<String> args,
      boolean isDaemon,
      int exitCode) {
    return new Finished(commandName, args, isDaemon, exitCode);
  }

  public static class Started extends CommandEvent {
    private Started(String commandName, ImmutableList<String> args, boolean isDaemon) {
      super(commandName, args, isDaemon);
    }

    @Override
    public String getEventName() {
      return "CommandStarted";
    }
  }

  public static class Finished extends CommandEvent {
    private final int exitCode;

    private Finished(String commandName,
        ImmutableList<String> args,
        boolean isDaemon,
        int exitCode) {
      super(commandName, args, isDaemon);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return "CommandFinished";
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished) o;
      return getExitCode() == that.getExitCode();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getCommandName(), getArgs(), isDaemon(), getExitCode());
    }
  }
}
