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

import com.facebook.buck.tools.consistency.BuckStressRunner.StressorException;
import com.facebook.buck.tools.consistency.CliArgs.StressRuleKeyRunCommand;
import com.facebook.buck.tools.consistency.CliArgs.StressTargetHashRunCommand;
import com.facebook.buck.tools.consistency.CliArgs.TargetHashDiffCommand;
import com.facebook.buck.tools.consistency.DifferState.DiffResult;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffer.GraphTraversalException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.facebook.buck.tools.consistency.RuleKeyStressRunner.RuleKeyStressRunException;
import com.facebook.buck.tools.consistency.TargetHashFileParser.ParsedTargetsFile;
import com.facebook.buck.tools.consistency.TargetsStressRunner.TargetsStressRunException;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    RULE_KEY_DIFFERENCES_DETECTED(13),
    TARGET_HASHES_PARSE_ERROR(20),
    TARGET_HASHES_MAX_DIFFERENCES_FOUND(21),
    TARGET_HASHES_DIFFERENCES_DETECTED(22),
    TARGET_STRESS_DIR_CREATE_FAILED(30),
    TARGET_STRESS_PARSE_EXCEPTION(31),
    TARGET_STRESS_MAX_DIFFERENCES_FOUND(32),
    TARGET_STRESS_ERROR_RUNNING_STRESSOR(33),
    TARGET_STRESS_DIFFERENCES_FOUND(34),
    RULEKEY_STRESS_DIR_CREATE_FAILED(40),
    RULE_KEY_STRESS_PARSE_EXCEPTION(41),
    RULE_KEY_STRESS_MAX_DIFFERENCES_FOUND(42),
    RULE_KEY_STRESS_ERROR_RUNNING_STRESSOR(43),
    RULE_KEY_STRESS_TRAVERSAL_ERROR(44),
    RULE_KEY_STRESS_DIFFERENCES_FOUND(45);

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
      } else if (parsedArgs.cmd instanceof CliArgs.TargetHashDiffCommand) {
        return handleTargetHashDiffCommand((CliArgs.TargetHashDiffCommand) parsedArgs.cmd);
      } else if (parsedArgs.cmd instanceof CliArgs.StressTargetHashRunCommand) {
        return handleStressTargetHashRunCommand(
            (CliArgs.StressTargetHashRunCommand) parsedArgs.cmd);
      } else if (parsedArgs.cmd instanceof CliArgs.StressRuleKeyRunCommand) {
        return handleStressRuleKeyRunCommand((CliArgs.StressRuleKeyRunCommand) parsedArgs.cmd);
      } else {
        return ReturnCode.UNHANDLED_SUBCOMMAND;
      }
    }
  }

  private static ReturnCode handleStressTargetHashRunCommand(StressTargetHashRunCommand args) {
    Callable<TargetsDiffer> differFactory =
        () -> {
          DifferState differState = new DifferState(args.maxDifferences);
          DiffPrinter diffPrinter = new DiffPrinter(System.out, args.useColor);
          TargetsDiffer differ = new TargetsDiffer(diffPrinter, differState);
          return differ;
        };

    BuckStressRunner stressRunner = new BuckStressRunner();
    TargetsStressRunner targetsStressRunner =
        new TargetsStressRunner(differFactory, "buck", args.buckArgs, args.targets);

    Path tempDirectory = null;
    try {
      tempDirectory = Files.createTempDirectory("targets-stress-run");
    } catch (IOException e) {
      System.err.println(
          String.format("Could not create temporary directory for stress run: %s", e.getMessage()));
      return ReturnCode.TARGET_STRESS_DIR_CREATE_FAILED;
    }
    try {
      List<BuckRunner> buckCommands =
          targetsStressRunner.getBuckRunners(
              args.runCount, Optional.ofNullable(args.repositoryDirectory).map(Paths::get));
      List<Path> outputFiles = stressRunner.run(buckCommands, tempDirectory, args.parallelism);
      targetsStressRunner.verifyNoChanges(
          outputFiles.get(0), outputFiles.subList(1, outputFiles.size()));
      return ReturnCode.NO_ERROR;
    } catch (ParseException e) {
      System.err.println(
          String.format("Could not parse resulting targets files: %s", e.getMessage()));
      return ReturnCode.TARGET_STRESS_PARSE_EXCEPTION;
    } catch (MaxDifferencesException e) {
      System.err.println(e.getMessage());
      return ReturnCode.TARGET_STRESS_MAX_DIFFERENCES_FOUND;
    } catch (StressorException e) {
      System.err.println(e.getMessage());
      return ReturnCode.TARGET_STRESS_ERROR_RUNNING_STRESSOR;
    } catch (TargetsStressRunException e) {
      System.err.println(e.getMessage());
      return ReturnCode.TARGET_STRESS_DIFFERENCES_FOUND;
    } finally {
      if (args.keepTempFiles) {
        System.err.println(String.format("Temporary files are kept at %s", tempDirectory));
      } else {
        try {
          Files.walk(tempDirectory)
              .sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .forEach(File::delete);
        } catch (IOException e) {
          System.err.println(
              String.format("Could not clean up temp directory at %s", tempDirectory));
        }
      }
    }
  }

  private static ReturnCode handleStressRuleKeyRunCommand(StressRuleKeyRunCommand args) {
    Callable<RuleKeyDiffer> differFactory =
        () -> {
          DifferState differState = new DifferState(args.maxDifferences);
          DiffPrinter diffPrinter = new DiffPrinter(System.out, args.useColor);
          RuleKeyDiffPrinter ruleKeyDiffPrinter = new RuleKeyDiffPrinter(diffPrinter, differState);
          RuleKeyDiffer differ = new RuleKeyDiffer(ruleKeyDiffPrinter);
          return differ;
        };

    BuckStressRunner stressRunner = new BuckStressRunner();
    RuleKeyStressRunner ruleKeyStressRunner =
        new RuleKeyStressRunner(differFactory, "buck", args.buckArgs, args.targets);

    Path tempDirectory = null;
    try {
      tempDirectory = Files.createTempDirectory("rulekeys-stress-run");
    } catch (IOException e) {
      System.err.println(
          String.format("Could not create temporary directory for stress run: %s", e.getMessage()));
      return ReturnCode.RULEKEY_STRESS_DIR_CREATE_FAILED;
    }
    try {
      List<BuckRunner> buckCommands =
          ruleKeyStressRunner.getBuckRunners(
              args.runCount,
              tempDirectory,
              Optional.ofNullable(args.repositoryDirectory).map(Paths::get));
      System.out.println(String.format("Commands: %s", buckCommands));
      List<Path> outputFiles =
          stressRunner
              .run(buckCommands, tempDirectory, args.parallelism)
              .stream()
              .map(
                  stdout ->
                      stdout.resolveSibling(
                          stdout.getFileName().toString().replace(".log", ".bin.log")))
              .collect(Collectors.toList());
      ruleKeyStressRunner.verifyNoChanges(
          outputFiles.get(0), outputFiles.subList(1, outputFiles.size()));
      return ReturnCode.NO_ERROR;
    } catch (ParseException e) {
      System.err.println(
          String.format("Could not parse resulting rule key files: %s", e.getMessage()));
      return ReturnCode.RULE_KEY_STRESS_PARSE_EXCEPTION;
    } catch (MaxDifferencesException e) {
      System.err.println(e.getMessage());
      return ReturnCode.RULE_KEY_STRESS_MAX_DIFFERENCES_FOUND;
    } catch (StressorException e) {
      System.err.println(e.getMessage());
      return ReturnCode.RULE_KEY_STRESS_ERROR_RUNNING_STRESSOR;
    } catch (GraphTraversalException e) {
      System.err.println(e.getMessage());
      return ReturnCode.RULE_KEY_STRESS_TRAVERSAL_ERROR;
    } catch (RuleKeyStressRunException e) {
      System.err.println(e.getMessage());
      return ReturnCode.RULE_KEY_STRESS_DIFFERENCES_FOUND;
    } finally {
      if (args.keepTempFiles) {
        System.err.println(String.format("Temporary files are kept at %s", tempDirectory));
      } else {
        try {
          Files.walk(tempDirectory)
              .sorted(Comparator.reverseOrder())
              .map(Path::toFile)
              .forEach(File::delete);
        } catch (IOException e) {
          System.err.println(
              String.format("Could not clean up temp directory at %s", tempDirectory));
        }
      }
    }
  }

  private static ReturnCode handleTargetHashDiffCommand(TargetHashDiffCommand args) {
    TargetHashFileParser parser = new TargetHashFileParser();
    ParsedTargetsFile originalFile = null;
    ParsedTargetsFile newFile = null;
    ExecutorService service = Executors.newFixedThreadPool(4);

    Future<ParsedTargetsFile> originalFileFuture =
        service.submit(() -> parser.parseFile(Paths.get(args.originalLogFile)));
    Future<ParsedTargetsFile> newFileFuture =
        service.submit(() -> parser.parseFile(Paths.get(args.newLogFile)));

    try {
      originalFile = originalFileFuture.get();
      newFile = newFileFuture.get();

      DifferState differState = new DifferState(args.maxDifferences);
      DiffPrinter diffPrinter = new DiffPrinter(System.out, args.useColor);
      TargetsDiffer targetsDiffer = new TargetsDiffer(diffPrinter, differState);

      DiffResult result = targetsDiffer.printDiff(originalFile, newFile);
      if (result == DiffResult.CHANGES_FOUND) {
        return ReturnCode.TARGET_HASHES_DIFFERENCES_DETECTED;
      } else {
        return ReturnCode.NO_ERROR;
      }
    } catch (ExecutionException e) {
      if (originalFile == null) {
        System.err.println(
            String.format("Error parsing %s: %s", args.originalLogFile, e.getCause().getMessage()));
      } else {
        System.err.println(
            String.format("Error parsing %s: %s", args.newLogFile, e.getCause().getMessage()));
      }
      return ReturnCode.TARGET_HASHES_PARSE_ERROR;
    } catch (MaxDifferencesException e) {
      System.err.println(e.getMessage());
      return ReturnCode.TARGET_HASHES_MAX_DIFFERENCES_FOUND;
    } catch (InterruptedException e) {
      e.printStackTrace();
      return ReturnCode.THREADING_ERROR;
    } finally {
      System.err.println();
      if (originalFile != null) {
        System.err.println(
            String.format(
                "Parsed %s in %s ms", args.originalLogFile, originalFile.parseTime.toMillis()));
      }
      if (newFile != null) {
        System.err.println(
            String.format("Parsed %s in %s ms", args.newLogFile, newFile.parseTime.toMillis()));
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
      printer.printFile(Paths.get(args.logFile));
      return ReturnCode.NO_ERROR;
    } catch (ParseException e) {
      System.err.println(String.format("Error parsing %s: %s", args.logFile, e.getMessage()));
      return ReturnCode.RULE_KEY_PARSE_ERROR;
    }
  }

  private static ReturnCode handleRuleKeyDiffCommand(CliArgs.RuleKeyDiffCommand args) {
    RuleKeyLogFileReader reader = new RuleKeyLogFileReader();
    RuleKeyFileParser fileParser = new RuleKeyFileParser(reader);
    Optional<ParsedRuleKeyFile> originalFile = Optional.empty();
    Optional<ParsedRuleKeyFile> newFile = Optional.empty();
    ExecutorService service = Executors.newFixedThreadPool(4);

    Future<ParsedRuleKeyFile> originalFileFuture =
        service.submit(
            () ->
                fileParser.parseFile(
                    Paths.get(args.originalLogFile), ImmutableSet.copyOf(args.targetNames)));
    Future<ParsedRuleKeyFile> newFileFuture =
        service.submit(
            () ->
                fileParser.parseFile(
                    Paths.get(args.newLogFile), ImmutableSet.copyOf(args.targetNames)));

    try {
      originalFile = Optional.of(originalFileFuture.get());
      newFile = Optional.of(newFileFuture.get());

      DiffPrinter diffPrinter = new DiffPrinter(System.out, args.useColor);
      DifferState differState = new DifferState(args.maxDifferences);
      RuleKeyDiffPrinter ruleKeyDiffPrinter = new RuleKeyDiffPrinter(diffPrinter, differState);
      RuleKeyDiffer differ = new RuleKeyDiffer(ruleKeyDiffPrinter);
      DiffResult result = differ.printDiff(originalFile.get(), newFile.get());

      if (result == DiffResult.CHANGES_FOUND) {
        return ReturnCode.RULE_KEY_DIFFERENCES_DETECTED;
      } else {
        return ReturnCode.NO_ERROR;
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
