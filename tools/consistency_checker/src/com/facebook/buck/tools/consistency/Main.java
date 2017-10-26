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

import com.facebook.buck.tools.consistency.RuleKeyDiffer.GraphTraversalException;
import com.facebook.buck.tools.consistency.RuleKeyDifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedFile;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Main entry point into the consistency checker. This tool finds differences between rule keys,
 * target graphs, and can ensure that the target graph is deterministic.
 */
public class Main {

  /** All of the valid return codes that we can have */
  public enum ReturnCode {
    NO_ERROR(0),
    HELP_REQUESTED(2),
    UNKNOWN_ARGUMENTS(3),
    UNHANDLED_SUBCOMMAND(4),
    THREADING_ERROR(5),
    RULE_KEY_PARSE_ERROR(10),
    RULE_KEY_TRAVERSAL_ERROR(11),
    RULE_KEY_MAX_DIFFERENCES_FOUND(12),
    RULE_KEY_DIFFERENCES_DETECTED(13);

    public final int value;

    ReturnCode(int value) {
      this.value = value;
    }
  }

  /** A simple scope that prints out how long anything inside the scope took to run to stderr */
  static class CommandTimer implements AutoCloseable {
    private final long startTime;
    private final PrintStream outStream;

    CommandTimer(PrintStream outStream) {
      this.outStream = outStream;
      this.startTime = System.nanoTime();
    }

    @Override
    public void close() {
      Duration runTime = Duration.ofNanos(System.nanoTime() - startTime);
      outStream.println(String.format("Total runtime: %s ms", runTime.toMillis()));
    }
  }

  /** Main entry into consistency checker */
  public static void main(String[] args) {
    System.exit(run(args).value);
  }

  private static ReturnCode run(String[] args) {

    CliArgs parsedArgs = new CliArgs();
    CmdLineParser argParser = new CmdLineParser(parsedArgs);
    List<String> argsList = Arrays.asList(args);

    try {
      argParser.parseArgument(args);
      if (parsedArgs.wasHelpRequested(argsList)) {
        CliArgs.printUsage(Optional.empty(), argsList, System.err);
        return ReturnCode.HELP_REQUESTED;
      }
    } catch (CmdLineException e) {
      if (parsedArgs.wasHelpRequested(argsList)) {
        CliArgs.printUsage(Optional.empty(), argsList, System.err);
        return ReturnCode.HELP_REQUESTED;
      } else {
        CliArgs.printUsage(Optional.of(e), Arrays.asList(args), System.err);
        return ReturnCode.UNKNOWN_ARGUMENTS;
      }
    }

    try (CommandTimer ignored = new CommandTimer(System.err)) {
      if (parsedArgs.cmd instanceof CliArgs.PrintCliCommand) {
        return handlePrintCommand((CliArgs.PrintCliCommand) parsedArgs.cmd);
      } else if (parsedArgs.cmd instanceof CliArgs.RuleKeyDiffCommand) {
        return handleRuleKeyDiffCommand((CliArgs.RuleKeyDiffCommand) parsedArgs.cmd);
      } else {
        return ReturnCode.UNHANDLED_SUBCOMMAND;
      }
    }
  }

  private static ReturnCode handlePrintCommand(CliArgs.PrintCliCommand args) {
    RuleKeyLogFileReader reader = new RuleKeyLogFileReader();
    Optional<Pattern> nameFilter = Optional.ofNullable(args.nameFilter).map(Pattern::compile);
    Optional<String> keysFilter = Optional.ofNullable(args.keysFilter);
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(System.out, reader, nameFilter, keysFilter, args.limit);

    try {
      printer.printFile(args.logFile);
      return ReturnCode.NO_ERROR;
    } catch (ParseException e) {
      System.err.println(String.format("Error parsing %s: %s", args.logFile, e.getMessage()));
      return ReturnCode.RULE_KEY_PARSE_ERROR;
    }
  }

  private static ReturnCode handleRuleKeyDiffCommand(CliArgs.RuleKeyDiffCommand args) {
    RuleKeyLogFileReader reader = new RuleKeyLogFileReader();
    RuleKeyFileParser fileParser = new RuleKeyFileParser(reader);
    Optional<ParsedFile> originalFile = Optional.empty();
    Optional<ParsedFile> newFile = Optional.empty();
    ExecutorService service = Executors.newFixedThreadPool(4);

    Future<ParsedFile> originalFileFuture =
        service.submit(() -> fileParser.parseFile(args.originalLogFile, args.targetName));
    Future<ParsedFile> newFileFuture =
        service.submit(() -> fileParser.parseFile(args.newLogFile, args.targetName));

    try {
      originalFile = Optional.of(originalFileFuture.get());
      newFile = Optional.of(newFileFuture.get());

      RuleKeyDifferState differState = new RuleKeyDifferState(args.maxDifferences);
      RuleKeyDiffPrinter diffPrinter =
          new RuleKeyDiffPrinter(System.out, args.useColor, differState);
      RuleKeyDiffer differ = new RuleKeyDiffer(diffPrinter);
      differ.printDiff(originalFile.get(), newFile.get());

      if (differState.getFoundDifferences() == 0) {
        return ReturnCode.NO_ERROR;
      } else {
        return ReturnCode.RULE_KEY_DIFFERENCES_DETECTED;
      }
    } catch (ExecutionException e) {
      if (!originalFile.isPresent()) {
        System.err.println(
            String.format("Error parsing %s: %s", args.originalLogFile, e.getCause().getMessage()));
      } else {
        System.err.println(
            String.format("Error parsing %s: %s", args.newLogFile, e.getCause().getMessage()));
      }
      return ReturnCode.RULE_KEY_PARSE_ERROR;
    } catch (GraphTraversalException e) {
      System.err.println(
          String.format(
              "Error traversing rule key graph. One or more file may be incorrectly formatted: %s",
              e.getMessage()));
      return ReturnCode.RULE_KEY_TRAVERSAL_ERROR;
    } catch (MaxDifferencesException e) {
      System.err.println(e.getMessage());
      return ReturnCode.RULE_KEY_MAX_DIFFERENCES_FOUND;
    } catch (InterruptedException e) {
      e.printStackTrace();
      return ReturnCode.THREADING_ERROR;
    } finally {
      System.err.println();
      if (originalFile.isPresent()) {
        System.err.println(
            String.format(
                "Parsed %s in %s ms",
                args.originalLogFile, originalFile.get().parseTime.toMillis()));
      }
      if (newFile.isPresent()) {
        System.err.println(
            String.format(
                "Parsed %s in %s ms", args.newLogFile, newFile.get().parseTime.toMillis()));
      }
    }
  }
}
