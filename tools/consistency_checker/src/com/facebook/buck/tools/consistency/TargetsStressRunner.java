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
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.facebook.buck.tools.consistency.TargetHashFileParser.ParsedTargetsFile;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Runs buck targets several times trying to find differences between target hashes when run with
 * the same arguments
 */
public class TargetsStressRunner {

  private final Callable<TargetsDiffer> differFactory;
  private final Optional<String> interpreter;
  private final String buckBinPath;
  private final List<String> buckArgs;
  private final List<String> targets;

  /**
   * Creates an instance of {@link TargetsStressRunner}
   *
   * @param differFactory A method that creates a {@link TargetsDiffer}
   * @param buckBinPath The 'buck' command. Either a path, or an alias
   * @param buckArgs Additional arguments that should be added between "targets" and the targets
   *     list
   * @param targets The list of targets to operate on
   */
  public TargetsStressRunner(
      Callable<TargetsDiffer> differFactory,
      String buckBinPath,
      List<String> buckArgs,
      List<String> targets) {
    this(differFactory, Optional.empty(), buckBinPath, buckArgs, targets);
  }

  /**
   * Creates an instance of {@link TargetsStressRunner}
   *
   * @param interpreter The interpreter to prefix the command with
   * @param differFactory A method that creates a {@link TargetsDiffer}
   * @param buckBinPath The 'buck' command. Either a path, or an alias
   * @param buckArgs Additional arguments that should be added between "targets" and the targets
   *     list
   * @param targets The list of targets to operate on
   */
  public TargetsStressRunner(
      Callable<TargetsDiffer> differFactory,
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
   * @param repositoryPath The path to run all commands from, or pwd if not provided
   * @return A list of command runners
   */
  public List<BuckRunner> getBuckRunners(int numRuns, Optional<Path> repositoryPath) {
    List<String> targetArgs =
        ImmutableList.of(
            "--show-target-hash",
            "--show-transitive-target-hashes",
            "--target-hash-file-mode=PATHS_ONLY");
    return IntStream.range(0, numRuns)
        .mapToObj(
            i -> {
              ArrayList<String> targetsRandom = new ArrayList<>(targets);
              Collections.shuffle(targetsRandom);
              return new BuckRunner(
                  interpreter,
                  buckBinPath,
                  "targets",
                  buckArgs,
                  targetArgs,
                  targetsRandom,
                  repositoryPath,
                  true);
            })
        .collect(Collectors.toList());
  }

  /**
   * Verifies that there are no changes between several targets files
   *
   * @param firstOutputFile The first file containing output from `buck targets`
   * @param outputFiles List of subsequent files containing output from `buck targets` that should
   *     be compared
   * @throws ParseException One of the files has invalid content
   * @throws MaxDifferencesException One of the comparisons had too many differences between the
   *     files
   * @throws TargetsStressRunException There was a differences between two of the files
   */
  public void verifyNoChanges(Path firstOutputFile, List<Path> outputFiles)
      throws ParseException, MaxDifferencesException, TargetsStressRunException {
    TargetHashFileParser parser = new TargetHashFileParser();
    ParsedTargetsFile originalFile = parser.parseFile(firstOutputFile);
    for (Path file : outputFiles) {
      ParsedTargetsFile newTargetsFile = parser.parseFile(file);
      TargetsDiffer targetsDiffer = null;
      try {
        targetsDiffer = differFactory.call();
      } catch (Exception e) {
        throw new TargetsStressRunException(
            String.format("Error creating differ: %s", e.getMessage()), e);
      }
      if (targetsDiffer.printDiff(originalFile, newTargetsFile) == DiffResult.CHANGES_FOUND) {
        throw new TargetsStressRunException(
            String.format(
                "Found differences between %s and %s",
                originalFile.filename, newTargetsFile.filename));
      }
    }
  }

  /** Exception that is thrown when the targets stress run did not complete successfully */
  class TargetsStressRunException extends Exception {
    public TargetsStressRunException(String message) {
      super(message);
    }

    public TargetsStressRunException(String message, Throwable e) {
      super(message, e);
    }
  }
}
