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

package com.facebook.buck.tools.consistency;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommandHandler;
import org.kohsuke.args4j.spi.SubCommands;

/** The common set of command line arguments for the consistency checker */
public class CliArgs extends CliCommand {
  public CliArgs() {
    super("");
  }

  /** The command line arguments for the 'print' command */
  public static class PrintCliCommand extends CliCommand {
    public PrintCliCommand() {
      super("Print human readable representations of rule keys");
    }

    @Argument(
      metaVar = "log file",
      usage = "Log file containing thrift serialized rulekeys",
      index = 0,
      required = true
    )
    String logFile;

    @Option(
      name = "--limit",
      usage = "If specified, stop printing after this many rules have been printed",
      required = false
    )
    long limit = Long.MAX_VALUE;
  }

  @Argument(
    handler = SubCommandHandler.class,
    required = true,
    metaVar = "subcommand",
    usage = "The subcommand to run"
  )
  @SubCommands({@SubCommand(name = "print", impl = PrintCliCommand.class)})
  CliCommand cmd;

  /**
   * Prints the usage information for either the main command, or the supplied subcommand
   *
   * @param exception The parse exception that caused us to need to print usage info, or empty if no
   *     error message needs to be printed
   * @param args The original list of command line arguments
   * @param out The stream to write showHelp information to
   */
  public static void printUsage(
      Optional<CmdLineException> exception, List<String> args, PrintStream out) {
    out.println("A tool for finding various types of inconsistency in buck\n");
    if (!args.contains("--showHelp") && exception.isPresent()) {
      out.println(exception.get().getMessage());
    }
    if (args.size() > 0 && args.get(0).equals("print")) {
      printUsage(PrintCliCommand.class, out);
      return;
    }
    printUsage(CliArgs.class, out);
  }

  private static void printUsage(Class<? extends CliCommand> clazz, PrintStream out) {
    try {
      CliCommand instance = clazz.newInstance();
      out.println(instance.getDescription());
      new CmdLineParser(instance).printUsage(out);
    } catch (IllegalAccessException e) {
      out.println(String.format("Could not get showHelp message: %s", e.getMessage()));
    } catch (InstantiationException e) {
      out.println(String.format("Could not get showHelp message: %s", e.getMessage()));
    }
  }
}
