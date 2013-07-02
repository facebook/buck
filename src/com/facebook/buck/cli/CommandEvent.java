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

import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Objects;

/**
 * Events tracking the start and stop of a buck command.
 */
public abstract class CommandEvent extends BuckEvent {
  private final String commandName;
  private final boolean isDaemon;

  protected CommandEvent(String commandName, boolean isDaemon) {
    this.commandName = commandName;
    this.isDaemon = isDaemon;
  }

  public String getCommandName() {
    return commandName;
  }

  public boolean isDaemon() {
    return isDaemon;
  }

  @Override
  protected String getValueString() {
    return String.format("%s, isDaemon: %b", commandName, isDaemon);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CommandEvent)) {
      return false;
    }

    CommandEvent that = (CommandEvent)o;

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getCommandName(), that.getCommandName()) &&
        Objects.equal(isDaemon(), that.isDaemon());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getCommandName(), isDaemon());
  }

  public static Started started(String commandName, boolean isDaemon) {
    return new Started(commandName, isDaemon);
  }

  public static Finished finished(String commandName, boolean isDaemon, int exitCode) {
    return new Finished(commandName, isDaemon, exitCode);
  }
  public static class Started extends CommandEvent {
    protected Started(String commandName, boolean isDaemon) {
      super(commandName, isDaemon);
    }

    @Override
    protected String getEventName() {
      return "CommandStarted";
    }
  }

  public static class Finished extends CommandEvent {
    private final int exitCode;

    protected Finished(String commandName, boolean isDaemon, int exitCode) {
      super(commandName, isDaemon);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    protected String getEventName() {
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
      return Objects.hashCode(getCommandName(), isDaemon(), getExitCode());
    }
  }
}
