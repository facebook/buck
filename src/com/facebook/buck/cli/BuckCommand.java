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

package com.facebook.buck.cli;

import com.google.common.base.Optional;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommands;

import java.io.IOException;
import java.io.PrintStream;

import javax.annotation.Nullable;

public class BuckCommand extends AbstractContainerCommand {

  @Argument(handler = AdditionalOptionsSubCommandHandler.class)
  @SubCommands({
      @SubCommand(name = "audit", impl = AuditCommand.class),
      @SubCommand(name = "build", impl = BuildCommand.class),
      @SubCommand(name = "cache", impl = CacheCommand.class),
      @SubCommand(name = "clean", impl = CleanCommand.class),
      @SubCommand(name = "fetch", impl = FetchCommand.class),
      @SubCommand(name = "install", impl = InstallCommand.class),
      @SubCommand(name = "project", impl = ProjectCommand.class),
      @SubCommand(name = "quickstart", impl = QuickstartCommand.class),
      @SubCommand(name = "run", impl = RunCommand.class),
      @SubCommand(name = "server", impl = ServerCommand.class),
      @SubCommand(name = "targets", impl = TargetsCommand.class),
      @SubCommand(name = "test", impl = TestCommand.class),
      @SubCommand(name = "uninstall", impl = UninstallCommand.class),
  })
  @Nullable
  Command subcommand;

  @Option(
      name = "--version",
      aliases = {"-V"},
      usage = "Show version number.")
  private boolean version;

  @Option(
      name = "--help",
      usage = "Shows this screen and exits.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean helpScreen;

  public boolean showVersion() {
    return version;
  }

  @Override
  public int run(CommandRunnerParams params) throws IOException, InterruptedException {
    if (subcommand == null) {
      if (showVersion()) {
        return new VersionCommand().run(params);
      }

      PrintStream stdErr = params.getConsole().getStdErr();
      printUsage(stdErr);

      return 1;
    }
    return subcommand.run(params);
  }

  @Override
  public boolean isReadOnly() {
    return subcommand == null || subcommand.isReadOnly();
  }

  @Override
  public String getShortDescription() {
    return "Buck build tool";
  }

  @Override
  protected String getContainerCommandPrefix() {
    return "buck";
  }

  @Override
  public Optional<Command> getSubcommand() {
    return Optional.fromNullable(subcommand);
  }

}
