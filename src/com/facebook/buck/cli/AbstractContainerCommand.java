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

import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

public abstract class AbstractContainerCommand extends CommandWithPluginManager {

  @Option(
      name = "--help",
      aliases = {"-h"},
      usage = "Shows this screen and exits.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean helpScreen;

  @Option(
      name = "--flagfile",
      metaVar = "FILE",
      usage = "File to read command line arguments from.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private String[] files;

  protected String getSubcommandsFieldName() {
    return "subcommand";
  }

  protected abstract Optional<Command> getSubcommand();

  protected abstract String getContainerCommandPrefix();

  @Override
  public Optional<ExitCode> runHelp(PrintStream stream) {
    if (getSubcommand().isPresent()) {
      return getSubcommand().get().runHelp(stream);
    }
    if (helpScreen) {
      printUsage(stream);
      return Optional.of(ExitCode.SUCCESS);
    }
    return Optional.empty();
  }

  @Override
  public ExitCode run(CommandRunnerParams params) throws IOException, InterruptedException {
    Optional<Command> subcommand = getSubcommand();
    if (subcommand.isPresent()) {
      return subcommand.get().run(params);
    }

    printUsage(params.getConsole().getStdErr());
    return ExitCode.COMMANDLINE_ERROR;
  }

  @Override
  public void printUsage(PrintStream stream) {
    String prefix = getContainerCommandPrefix();

    CommandHelper.printShortDescription(this, stream);
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

    int lengthOfLongestCommand =
        Arrays.stream(subCommands.value())
            .map(subcmd -> subcmd.name().length())
            .max(Integer::compare)
            .orElse(0);

    for (SubCommand subCommand : subCommands.value()) {
      Command command;
      try {
        command = (Command) subCommand.impl().newInstance();
      } catch (IllegalAccessException | InstantiationException e) {
        throw new RuntimeException(e);
      }
      String name = subCommand.name().toLowerCase();
      stream.printf(
          "  %s%s  %s%n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }
    stream.println();

    stream.println("Options:");
    new AdditionalOptionsCmdLineParser(getPluginManager(), this).printUsage(stream);
    stream.println();
  }

  @Override
  public CellConfig getConfigOverrides(ImmutableMap<CellName, Path> cellMapping) {
    Optional<Command> cmd = getSubcommand();
    return cmd.isPresent() ? cmd.get().getConfigOverrides(cellMapping) : CellConfig.of();
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
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    if (!getSubcommand().isPresent()) {
      return ImmutableList.of();
    }
    return getSubcommand().get().getEventListeners(executorPool, scheduledExecutorService);
  }

  @Override
  public boolean performsBuild() {
    if (!getSubcommand().isPresent()) {
      return false;
    }
    return getSubcommand().get().performsBuild();
  }

  @Override
  public ImmutableList<String> getTargetPlatforms() {
    return getSubcommand()
        .orElseThrow(
            () ->
                new IllegalArgumentException("Target platforms are not supported in this command"))
        .getTargetPlatforms();
  }
}
