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

package com.facebook.buck.cli;

import com.facebook.buck.config.CellConfig;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.LogConfigSetup;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.OptionalInt;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

public abstract class AbstractContainerCommand implements Command {

  @Option(
    name = "--help",
    aliases = {"-h"},
    usage = "Shows this screen and exits."
  )
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean helpScreen;

  protected String getSubcommandsFieldName() {
    return "subcommand";
  }

  protected abstract Optional<Command> getSubcommand();

  protected abstract String getContainerCommandPrefix();

  @Override
  public OptionalInt runHelp(PrintStream stream) {
    if (getSubcommand().isPresent()) {
      return getSubcommand().get().runHelp(stream);
    } else if (helpScreen) {
      printUsage(stream);
      return OptionalInt.of(1);
    } else {
      return OptionalInt.empty();
    }
  }

  @Override
  public int run(CommandRunnerParams params) throws IOException, InterruptedException {
    Optional<Command> subcommand = getSubcommand();
    if (subcommand.isPresent()) {
      return subcommand.get().run(params);
    } else {
      printUsage(params.getConsole().getStdErr());
      return 1;
    }
  }

  @Override
  public void printUsage(PrintStream stream) {
    String prefix = getContainerCommandPrefix();

    stream.println("buck build tool");

    stream.println("Usage:");
    stream.println("  " + prefix + " [<options>]");
    stream.println("  " + prefix + " <command> --help");
    stream.println("  " + prefix + " <command> [<command-options>]");
    stream.println();

    stream.println("Available commands:");

    SubCommands subCommands;
    try {
      subCommands =
          this.getClass()
              .getDeclaredField(getSubcommandsFieldName())
              .getAnnotation(SubCommands.class);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
    int lengthOfLongestCommand = 0;
    for (SubCommand subCommand : subCommands.value()) {
      String name = subCommand.name();
      if (name.length() > lengthOfLongestCommand) {
        lengthOfLongestCommand = name.length();
      }
    }

    for (SubCommand subCommand : subCommands.value()) {
      Command command;
      try {
        command = (Command) subCommand.impl().newInstance();
      } catch (IllegalAccessException | InstantiationException e) {
        throw new RuntimeException(e);
      }
      String name = subCommand.name().toLowerCase();
      stream.printf(
          "  %s%s  %s\n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }
    stream.println();

    stream.println("Options:");
    new AdditionalOptionsCmdLineParser(this).printUsage(stream);
    stream.println();
  }

  @Override
  public CellConfig getConfigOverrides() {
    Optional<Command> cmd = getSubcommand();
    return cmd.isPresent() ? cmd.get().getConfigOverrides() : CellConfig.of();
  }

  @Override
  public LogConfigSetup getLogConfig() {
    Optional<Command> cmd = getSubcommand();
    return cmd.isPresent() ? cmd.get().getLogConfig() : LogConfigSetup.DEFAULT_SETUP;
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return false;
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners() {
    if (!getSubcommand().isPresent()) {
      return ImmutableList.of();
    }
    return getSubcommand().get().getEventListeners();
  }
}
