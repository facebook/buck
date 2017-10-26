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
import org.kohsuke.args4j.spi.ExplicitBooleanOptionHandler;
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

  /** The list of options used to do a diff of two rule key files */
  public static class RuleKeyDiffCommand extends CliCommand {

    public RuleKeyDiffCommand() {
      super("Print out the differences between two files with serialized rule keys");
    }

    @Argument(
      metaVar = "original log file",
      usage = "The original log file containing thrift serialized rulekeys",
      required = true,
      index = 0
    )
    String originalLogFile;

    @Argument(
      metaVar = "new log file",
      usage = "The new log file containing thrift serialized rulekeys after a change has been made",
      required = true,
      index = 1
    )
    String newLogFile;

    @Argument(
      metaVar = "target name",
      usage = "The root target to traverse from in each file",
      required = true,
      index = 2
    )
    String targetName;

    @Option(
      name = "--color",
      handler = ExplicitBooleanOptionHandler.class,
      usage = "Color the output"
    )
    boolean useColor = true;

    @Option(
      name = "--max-differences",
      usage =
          "The maximum number of differences to print. Any more will "
              + "result in a non-zero exit code"
    )
    int maxDifferences = RuleKeyDifferState.INFINITE_DIFFERENCES;
  }

  @Argument(
    handler = SubCommandHandler.class,
    required = true,
    metaVar = "subcommand",
    usage = "The subcommand to run"
  )
  @SubCommands({
    @SubCommand(name = "print", impl = PrintCliCommand.class),
    @SubCommand(name = "rule_key_diff", impl = RuleKeyDiffCommand.class)
  })
  CliCommand cmd;

  /**
   * Prints the usage information for either the main command, or the supplied subcommand
   *
   * @param exception The parse exception that caused us to need to print usage info, or empty if no
   *     error message needs to be printed
   * @param args The original list of command line arguments
   * @param out The stream to write help information to
   */
  public static void printUsage(
      Optional<CmdLineException> exception, List<String> args, PrintStream out) {
    out.println("A tool for finding various types of inconsistency in buck\n");
    if (exception.isPresent()) {
      out.println(exception.get().getMessage());
    }
    if (!printSubcommandUsage(args, out)) {
      printUsage(CliArgs.class, out);
    }
  }

  private static boolean printSubcommandUsage(List<String> args, PrintStream out) {
    if (args.size() > 0) {
      switch (args.get(0)) {
        case ("print"):
          printUsage(PrintCliCommand.class, out);
          return true;
        case ("rule_key_diff"):
          printUsage(RuleKeyDiffCommand.class, out);
          return true;
        default:
          break;
      }
    }
    return false;
  }

  private static void printUsage(Class<? extends CliCommand> clazz, PrintStream out) {
    try {
      CliCommand instance = clazz.newInstance();
      out.println(instance.getDescription());
      new CmdLineParser(instance).printUsage(out);
    } catch (IllegalAccessException e) {
      out.println(String.format("Could not get help message: %s", e.getMessage()));
    } catch (InstantiationException e) {
      out.println(String.format("Could not get help message: %s", e.getMessage()));
    }
  }

  /**
   * Determines whether something went wrong, or whether we are just showing the help message as
   * requested
   *
   * @param args The argv for the program
   * @return Whether the help argument was in the arguments or the parsed arguments object
   */
  public boolean wasHelpRequested(List<String> args) {
    return this.showHelp || (this.cmd != null && this.cmd.showHelp) || args.contains("--help");
  }
}
