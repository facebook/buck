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
import java.util.ArrayList;
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
        required = true)
    String logFile;

    @Option(
        name = "--limit",
        usage = "If specified, stop printing after this many rules have been printed",
        required = false)
    int limit = Integer.MAX_VALUE;

    @Option(
        name = "--name-filter",
        usage =
            "If specified, only print rules whose names match this regular expression. Mutually exclusive with --key-filter",
        required = false,
        forbids = "--key-filter")
    String nameFilter = null;

    @Option(
        name = "--key-filter",
        usage =
            "If specified, only print rules whose keys match this regular expression. Mutually exclusive with --name-filter",
        required = false,
        forbids = "--name-filter")
    String keysFilter = null;
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
        index = 0)
    String originalLogFile;

    @Argument(
        metaVar = "new log file",
        usage =
            "The new log file containing thrift serialized rulekeys after a change has been made",
        required = true,
        index = 1)
    String newLogFile;

    @Argument(
        metaVar = "target name",
        usage = "The root target to traverse from in each file",
        required = true)
    List<String> targetNames;

    @Option(
        name = "--color",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "Color the output")
    boolean useColor = true;

    @Option(
        name = "--max-differences",
        usage =
            "The maximum number of differences to print. Any more will "
                + "result in a non-zero exit code")
    int maxDifferences = DifferState.INFINITE_DIFFERENCES;
  }

  /** The list of options used to do a diff of two target hash files */
  public static class TargetHashDiffCommand extends CliCommand {

    public TargetHashDiffCommand() {
      super(
          "Print out the differences between two files with target names and hashes (e.g. from buck targets --show-target-hash)");
    }

    @Argument(
        metaVar = "original log file",
        usage = "The original log file containing target names and hashes",
        required = true,
        index = 0)
    String originalLogFile;

    @Argument(
        metaVar = "new log file",
        usage = "The new log file containing target names and hashes",
        required = true,
        index = 1)
    String newLogFile;

    @Option(
        name = "--color",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "Color the output")
    boolean useColor = true;

    @Option(
        name = "--max-differences",
        usage =
            "The maximum number of differences to print. Any more will "
                + "result in a non-zero exit code")
    int maxDifferences = DifferState.INFINITE_DIFFERENCES;
  }

  /** Arguments to handle running stress runs comparing target hash outputs from buck targets */
  public static class StressTargetHashRunCommand extends CliCommand {
    public StressTargetHashRunCommand() {
      super(
          "Runs buck targets several times, and checks to see if the target hashes change at all");
    }

    @Option(name = "--parallelism", usage = "The number of buck targets commands to run at once")
    int parallelism = 2;

    @Option(name = "--runs", usage = "The number of runs to do in total")
    int runCount = 5;

    @Option(
        name = "--buck-args",
        usage = "Arguments to send to buck when invoked. Can be specified multiple times")
    List<String> buckArgs = new ArrayList<>();

    @Option(
        name = "--max-differences",
        usage = "After this number of differences, stop doing comparisons of the target graphs")
    int maxDifferences = DifferState.INFINITE_DIFFERENCES;

    @Option(
        name = "--color",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "Color the output")
    boolean useColor = true;

    @Option(
        name = "--keep-temp-files",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "If set, do not delete temp files after completion")
    boolean keepTempFiles = false;

    @Option(
        name = "--repository-directory",
        usage = "If given, run buck commands from this directory")
    String repositoryDirectory = null;

    @Argument(
        metaVar = "target names",
        usage = "The targets that should have their hash (and all dependencies' hashes) checked",
        required = true)
    List<String> targets;
  }

  /** Arguments to handle running stress runs comparing target hash outputs from buck targets */
  public static class StressRuleKeyRunCommand extends CliCommand {
    public StressRuleKeyRunCommand() {
      super("Runs buck targets several times, and checks to see if the rule keys change at all");
    }

    @Option(name = "--parallelism", usage = "The number of buck targets commands to run at once")
    int parallelism = 2;

    @Option(name = "--runs", usage = "The number of runs to do in total")
    int runCount = 5;

    @Option(
        name = "--buck-args",
        usage = "Arguments to send to buck when invoked. Can be specified multiple times")
    List<String> buckArgs = new ArrayList<>();

    @Option(
        name = "--max-differences",
        usage = "After this number of differences, stop doing comparisons of the action graphs")
    int maxDifferences = DifferState.INFINITE_DIFFERENCES;

    @Option(
        name = "--color",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "Color the output")
    boolean useColor = true;

    @Option(
        name = "--keep-temp-files",
        handler = ExplicitBooleanOptionHandler.class,
        usage = "If set, do not delete temp files after completion")
    boolean keepTempFiles = false;

    @Option(
        name = "--repository-directory",
        usage = "If given, run buck commands from this directory")
    String repositoryDirectory = null;

    @Argument(
        metaVar = "target names",
        usage =
            "The targets that should have their rule keys (and all dependencies' rule keys) checked",
        required = true)
    List<String> targets;
  }

  @Argument(
      handler = SubCommandHandler.class,
      required = true,
      metaVar = "subcommand",
      usage = "The subcommand to run")
  @SubCommands({
    @SubCommand(name = "print", impl = PrintCliCommand.class),
    @SubCommand(name = "rule_key_diff", impl = RuleKeyDiffCommand.class),
    @SubCommand(name = "target_hash_diff", impl = TargetHashDiffCommand.class),
    @SubCommand(name = "stress_target_hash_run", impl = StressTargetHashRunCommand.class),
    @SubCommand(name = "stress_rulekey_run", impl = StressRuleKeyRunCommand.class)
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
        case ("target_hash_diff"):
          printUsage(TargetHashDiffCommand.class, out);
          return true;
        case ("rule_key_diff"):
          printUsage(RuleKeyDiffCommand.class, out);
          return true;
        case ("stress_target_hash_run"):
          printUsage(StressTargetHashRunCommand.class, out);
          return true;
        case ("stress_rulekey_run"):
          printUsage(StressRuleKeyRunCommand.class, out);
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
