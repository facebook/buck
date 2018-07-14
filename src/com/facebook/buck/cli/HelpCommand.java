/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;

public class HelpCommand extends AbstractCommand {

  @Argument private List<String> arguments = new ArrayList<>();

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "shows this screen (or the help page of the specified command) and exits.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    return run(params.getConsole().getStdErr());
  }

  /**
   * This overload runs the actual help subcommand.
   *
   * <p>Used by {@link BuckCommand#runHelp} to run the help subcommand without initializing {@link
   * CommandRunnerParams}.
   */
  public ExitCode run(PrintStream stream) {
    BuckCommand command = new BuckCommand();
    command.setPluginManager(getPluginManager());
    AdditionalOptionsCmdLineParser cmdLineParser =
        new AdditionalOptionsCmdLineParser(getPluginManager(), command);
    try {
      cmdLineParser.parseArgument(arguments);
    } catch (CmdLineException e) {
      command.printUsage(stream);
      return ExitCode.COMMANDLINE_ERROR;
    }

    if (command.getSubcommand().isPresent()) {
      command.getSubcommand().get().printUsage(stream);
    } else {
      command.printUsage(stream);
    }
    return ExitCode.SUCCESS;
  }

  @Override
  public void printUsage(PrintStream stream) {
    CommandHelper.printShortDescription(this, stream);
    stream.println("Usage:");
    stream.println("  buck help");
    stream.println("  buck help <command>");
    stream.println();
  }
}
