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

package com.facebook.buck.event;

import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;

/** Events tracking the start and stop of a buck command. */
public abstract class CommandEvent extends AbstractBuckEvent implements WorkAdvanceEvent {
  private final String commandName;
  private final ImmutableList<String> args;
  private final boolean isDaemon;
  private final long pid;

  /**
   * @param commandName The name of the Buck subcommand, such as {@code build} or {@code test}.
   * @param args The arguments passed to the subcommand. These are often build targets.
   * @param isDaemon Whether the daemon was in use.
   * @param pid The process ID of the process.
   */
  private CommandEvent(
      EventKey eventKey,
      String commandName,
      ImmutableList<String> args,
      boolean isDaemon,
      long pid) {
    super(eventKey);
    this.commandName = commandName;
    this.args = args;
    this.isDaemon = isDaemon;
    this.pid = pid;
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

  public long getPid() {
    return pid;
  }

  @Override
  protected String getValueString() {
    return String.format("%s, isDaemon: %b", commandName, isDaemon);
  }

  public static Started started(
      String commandName, ImmutableList<String> args, boolean isDaemon, long pid) {
    return new Started(commandName, args, isDaemon, pid);
  }

  public static Finished finished(Started started, ExitCode exitCode) {
    return new Finished(started, exitCode);
  }

  public static Interrupted interrupted(Started started, ExitCode exitCode) {
    return new Interrupted(started, exitCode);
  }

  public static class Started extends CommandEvent {
    private Started(String commandName, ImmutableList<String> args, boolean isDaemon, long pid) {
      super(EventKey.unique(), commandName, args, isDaemon, pid);
    }

    @Override
    public String getEventName() {
      return "CommandStarted";
    }
  }

  public static class Finished extends CommandEvent {
    private final ExitCode exitCode;

    private Finished(Started started, ExitCode exitCode) {
      super(
          started.getEventKey(),
          started.getCommandName(),
          started.getArgs(),
          started.isDaemon(),
          started.getPid());
      this.exitCode = exitCode;
    }

    public ExitCode getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return "CommandFinished";
    }
  }

  public static class Interrupted extends CommandEvent implements BuckEvent {
    private final ExitCode exitCode;

    private Interrupted(Started started, ExitCode exitCode) {
      super(
          started.getEventKey(),
          started.getCommandName(),
          started.getArgs(),
          started.isDaemon(),
          started.getPid());
      this.exitCode = exitCode;
    }

    public ExitCode getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return "CommandInterrupted";
    }
  }
}
