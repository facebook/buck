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

import com.facebook.buck.tools.consistency.DifferState.DiffResult;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffer.GraphTraversalException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Runs buck targets several times to see if the generated rule keys are different at all */
public class RuleKeyStressRunner {
  private final Callable<RuleKeyDiffer> differFactory;
  private final Optional<String> interpreter;
  private final String buckBinPath;
  private final List<String> buckArgs;
  private final List<String> targets;

  /**
   * Creates an instance of {@link RuleKeyStressRunner}
   *
   * @param differFactory A method that creates a {@link RuleKeyDiffer}
   * @param buckBinPath The 'buck' command. Either a path, or an alias
   * @param buckArgs Additional arguments that should be added between "targets" and the targets
   *     list
   * @param targets The list of targets to operate on
   */
  public RuleKeyStressRunner(
      Callable<RuleKeyDiffer> differFactory,
      String buckBinPath,
      List<String> buckArgs,
      List<String> targets) {
    this(differFactory, Optional.empty(), buckBinPath, buckArgs, targets);
  }

  /**
   * Creates an instance of {@link RuleKeyStressRunner} The extra interpreter argument is used
   * sometimes because windows does not let one execute scripts directly; the interpreter has to be
   * specified
   *
   * @param interpreter The interpreter to prefix the command with
   * @param differFactory A method that creates a {@link RuleKeyDiffer}
   * @param buckBinPath The 'buck' command. Either a path, or an alias
   * @param buckArgs Additional arguments that should be added between "targets" and the targets
   *     list
   * @param targets The list of targets to operate on
   */
  @VisibleForTesting
  public RuleKeyStressRunner(
      Callable<RuleKeyDiffer> differFactory,
      Optional<String> interpreter,
      String buckBinPath,
      List<String> buckArgs,
      List<String> targets) {
    this.differFactory = differFactory;
    this.interpreter = interpreter;
    this.buckBinPath = buckBinPath;
    this.buckArgs = buckArgs;
    this.targets = targets;
  }

  /**
   * Get a list of command runners that can be fed into {@link BuckStressRunner} that will run `buck
   * targets` to get target hashes
   *
   * @param numRuns The number of runs to do
   * @param logDir The directory to write binary log data out to
   * @param repositoryPath The path to run all commands from, or pwd if not provided
   * @return A list of command runners
   */
  public List<BuckRunner> getBuckRunners(int numRuns, Path logDir, Optional<Path> repositoryPath) {
    return IntStream.range(0, numRuns)
        .mapToObj(
            i -> {
              Path binLogPath = logDir.resolve(String.format("%s.bin.log", i));
              List<String> targetsArgs =
                  ImmutableList.of(
                      String.format("--rulekeys-log-path=%s", binLogPath.toAbsolutePath()),
                      "--show-rulekey",
                      "--show-transitive-rulekeys");
              ArrayList<String> targetsRandom = new ArrayList<>(targets);
              Collections.shuffle(targetsRandom);
              return new BuckRunner(
                  interpreter,
                  buckBinPath,
                  "targets",
                  buckArgs,
                  targetsArgs,
                  targetsRandom,
                  repositoryPath,
                  true);
            })
        .collect(Collectors.toList());
  }

  /**
   * Verifies that there are no changes between several rulekeys files
   *
   * @param firstOutputFile The first file containing binary rulekey data
   * @param outputFiles List of subsequent files containing binary rulekey data to be compared
   * @throws ParseException One of the files has invalid content
   * @throws MaxDifferencesException One of the comparisons had too many differences between the
   *     files
   * @throws RuleKeyStressRunException There was a difference between two of the files
   */
  public void verifyNoChanges(Path firstOutputFile, List<Path> outputFiles)
      throws ParseException, RuleKeyStressRunException, MaxDifferencesException,
          GraphTraversalException {
    RuleKeyFileParser parser = new RuleKeyFileParser(new RuleKeyLogFileReader());
    ImmutableSet<String> immutableTargets = ImmutableSet.copyOf(targets);
    ParsedRuleKeyFile originalFile =
        parser.parseFile(firstOutputFile.toAbsolutePath(), immutableTargets);
    for (Path file : outputFiles) {
      ParsedRuleKeyFile newRuleKeyFile = parser.parseFile(file, immutableTargets);
      RuleKeyDiffer ruleKeyDiffer = null;
      try {
        ruleKeyDiffer = differFactory.call();
      } catch (Exception e) {
        throw new RuleKeyStressRunException(
            String.format("Error creating differ: %s", e.getMessage()), e);
      }

      if (ruleKeyDiffer.printDiff(originalFile, newRuleKeyFile) == DiffResult.CHANGES_FOUND) {
        throw new RuleKeyStressRunException(
            String.format(
                "Found differences between %s and %s",
                originalFile.filename, newRuleKeyFile.filename));
      }
    }
  }

  /** Exception that is thrown when the rule key stress run did not complete successfully */
  class RuleKeyStressRunException extends Exception {
    public RuleKeyStressRunException(String message) {
      super(message);
    }

    public RuleKeyStressRunException(String message, Throwable e) {
      super(message, e);
    }
  }
}
