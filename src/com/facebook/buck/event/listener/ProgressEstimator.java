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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ProgressEvent;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class ProgressEstimator implements AutoCloseable {

  private static final Duration PROCESS_ESTIMATE_IO_TIMEOUT = Duration.ofMillis(5000);
  private final Path storageFile;

  private static final Logger LOG = Logger.get(ProgressEstimator.class);

  public static final String EXPECTED_NUMBER_OF_PARSED_RULES = "expectedNumberOfParsedRules";
  public static final String EXPECTED_NUMBER_OF_PARSED_BUCK_FILES =
      "expectedNumberOfParsedBUCKFiles";
  public static final String EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES =
      "expectedNumberOfGeneratedProjectFiles";
  public static final String PROGRESS_ESTIMATIONS_JSON = ".progressestimations.json";

  @Nullable private String command;

  private BuckEventBus buckEventBus;

  // This should only be accessed from within the executorService
  @Nullable private Map<String, Map<String, Number>> expectationsStorage;

  private final AtomicInteger numberOfParsedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfParsedBUCKFiles = new AtomicInteger(0);
  private final AtomicInteger numberOfGeneratedProjectFiles = new AtomicInteger(0);

  private final AtomicInteger expectedNumberOfParsedRules = new AtomicInteger(0);
  private final AtomicInteger expectedNumberOfParsedBUCKFiles = new AtomicInteger(0);
  private final AtomicInteger expectedNumberOfGeneratedProjectFiles = new AtomicInteger(0);

  private final AtomicInteger numberOfRules = new AtomicInteger(0);
  private final AtomicInteger numberOfStartedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfFinishedRules = new AtomicInteger(0);

  private final AtomicDouble parsingFilesProgress = new AtomicDouble(-1.0);
  private final AtomicDouble projectGenerationProgress = new AtomicDouble(-1.0);
  private final AtomicDouble buildProgress = new AtomicDouble(-1.0);

  private final ExecutorService executorService = Executors.newSingleThreadExecutor();
  // We expect more calls to this ProgressEstimator even after it is closed. In which case, we
  // should just return the data already computed, but not attempt to run any new estimation tasks.
  // This is because the ConsoleListeners are closed before the BuckEventBus closes, which means
  // that we can expect more events that triggers console events to occur.
  // Ideally we would change this behaviour, but doing so right now is going to break console
  // printing.
  private volatile boolean isClosed = false;

  public ProgressEstimator(Path storageFile, BuckEventBus buckEventBus) {
    this.storageFile = storageFile;
    this.command = null;
    this.buckEventBus = buckEventBus;
    this.expectationsStorage = null;
  }

  /**
   * Reset the stats associated with BuildRuleEvent counts. Can be used to reset progress estimation
   * for a second build instance.
   */
  public void resetBuildData() {
    numberOfRules.set(0);
    numberOfStartedRules.set(0);
    numberOfFinishedRules.set(0);
    buildProgress.set(-1.0);
  }

  /**
   * Sets the current command that we are estimating
   *
   * @param commandName the name of the command
   * @param commandArgs the arguments to the command
   * @return a future that completes when estimation calculation is complete
   */
  public Future<?> setCurrentCommand(String commandName, ImmutableList<String> commandArgs) {
    command = commandName + " " + Joiner.on(" ").join(commandArgs);
    return fillEstimationsForCommand(command);
  }

  /**
   * Updates the amount of rules done parsing
   *
   * @param amount the number of rules done parsing
   * @return a future that completes when estimation calculation is complete
   */
  public Future<?> didParseBuckRules(int amount) {
    numberOfParsedRules.addAndGet(amount);
    numberOfParsedBUCKFiles.incrementAndGet();

    return runAsync(() -> calculateParsingBuckFilesEstimatedProgress());
  }

  /**
   * Indicates that buck has finished parsing, updating the progress estimation accordingly
   *
   * @return a future that completes when estimation calculation is complete
   */
  public Future<?> didFinishParsing() {
    if (command != null) {
      return runAsync(
          () -> {
            expectedNumberOfParsedRules.set(numberOfParsedRules.get());
            expectedNumberOfParsedBUCKFiles.set(numberOfParsedBUCKFiles.get());
            calculateParsingBuckFilesEstimatedProgress();
            updateEstimatedBuckFilesParsingValues(command);
          });
    }
    return Futures.immediateFuture(null);
  }

  /**
   * Indicates that a project file has been generated
   *
   * @return a future that completes when estimation calculation is complete
   */
  public Future<?> didGenerateProjectForTarget() {
    return runAsync(
        () -> {
          numberOfGeneratedProjectFiles.incrementAndGet();
          calculateProjectFilesGenerationEstimatedProgress();
        });
  }

  /**
   * Indicates that project generation is finished
   *
   * @return a future that completes when estimation calculation is complete
   */
  public Future<?> didFinishProjectGeneration() {
    if (command != null) {
      return runAsync(
          () -> {
            expectedNumberOfGeneratedProjectFiles.set(numberOfGeneratedProjectFiles.get());
            calculateProjectFilesGenerationEstimatedProgress();
            updateEstimatedProjectGenerationValues(command);
          });
    }
    return Futures.immediateFuture(null);
  }

  public void setNumberOfRules(int count) {
    numberOfRules.set(Math.max(count, 0));
    if (numberOfRules.intValue() == 0) {
      LOG.warn("Got 0 rules to process, progress will not be computed");
    }
    calculateBuildProgress();
  }

  public void didStartRule() {
    numberOfStartedRules.incrementAndGet();
    calculateBuildProgress();
  }

  public void didResumeRule() {
    calculateBuildProgress();
  }

  public void didSuspendRule() {
    calculateBuildProgress();
  }

  public void didFinishRule() {
    numberOfFinishedRules.incrementAndGet();
    calculateBuildProgress();
  }

  public void didStartBuild() {
    numberOfStartedRules.set(0);
    numberOfFinishedRules.set(0);
  }

  public void didFinishBuild() {
    int rulesCount = numberOfRules.intValue();
    if (rulesCount > 0) {
      numberOfStartedRules.set(rulesCount);
      numberOfFinishedRules.set(rulesCount);
      calculateBuildProgress();
    }
  }

  private Future<?> fillEstimationsForCommand(String aCommand) {
    return runAsync(
        () -> {
          preloadEstimationsFromStorageFile();
          Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
          if (commandEstimations != null) {
            if (commandEstimations.containsKey(EXPECTED_NUMBER_OF_PARSED_RULES)
                && commandEstimations.containsKey(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES)) {
              expectedNumberOfParsedRules.set(
                  commandEstimations.get(EXPECTED_NUMBER_OF_PARSED_RULES).intValue());
              expectedNumberOfParsedBUCKFiles.set(
                  commandEstimations.get(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES).intValue());
            }
            if (commandEstimations.containsKey(EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES)) {
              expectedNumberOfGeneratedProjectFiles.set(
                  commandEstimations.get(EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES).intValue());
            }
            calculateParsingBuckFilesEstimatedProgress();
          }
        });
  }

  /** This should be run from within the {@code executorService} */
  private void preloadEstimationsFromStorageFile() {
    expectationsStorage =
        runWithTimeout(
                () -> {
                  Map<String, Map<String, Number>> map = null;
                  if (Files.exists(storageFile)) {
                    try {
                      byte[] bytes = Files.readAllBytes(storageFile);
                      map =
                          ObjectMappers.READER.readValue(
                              ObjectMappers.createParser(bytes),
                              new TypeReference<HashMap<String, Map<String, Number>>>() {});
                    } catch (Exception e) {
                      LOG.warn("Unable to load progress estimations from file: " + e.getMessage());
                    }
                  }
                  if (map == null) {
                    map = new HashMap<>();
                  }
                  return map;
                })
            .orElse(null);
  }

  @Nullable
  private Map<String, Number> getEstimationsForCommand(String aCommand) {
    if (expectationsStorage == null || aCommand == null) {
      return null;
    }
    Map<String, Number> commandEstimations = expectationsStorage.get(aCommand);
    if (commandEstimations == null) {
      commandEstimations = new HashMap<>();
      expectationsStorage.put(aCommand, commandEstimations);
    }
    return commandEstimations;
  }

  private void updateEstimatedProjectGenerationValues(String aCommand) {
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      commandEstimations.put(
          EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES, numberOfGeneratedProjectFiles);
      saveEstimatedValues();
    }
  }

  private void updateEstimatedBuckFilesParsingValues(String aCommand) {
    // runs in executor
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      commandEstimations.put(EXPECTED_NUMBER_OF_PARSED_RULES, numberOfParsedRules);
      commandEstimations.put(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES, numberOfParsedBUCKFiles);
      saveEstimatedValues();
    }
  }

  /** This should be run from within the {@code executorService} */
  private void saveEstimatedValues() {
    runWithTimeout(
        () -> {
          if (expectationsStorage == null) {
            return null;
          }
          try {
            Files.createDirectories(storageFile.getParent());
          } catch (IOException e) {
            LOG.warn(
                "Unable to make path for storage %s: %s",
                storageFile.toString(), e.getLocalizedMessage());
            return null;
          }
          try {
            ObjectMappers.WRITER.writeValue(storageFile.toFile(), expectationsStorage);
          } catch (IOException e) {
            LOG.warn("Unable to save progress expectations: " + e.getLocalizedMessage());
          }
          return null;
        });
  }

  /**
   * @return Estimated progress of parsing files stage. If return value is absent, it's impossible
   *     to compute the estimated progress.
   */
  public Optional<Double> getEstimatedProgressOfParsingBuckFiles() {
    return wrapValueIntoOptional(parsingFilesProgress.get());
  }

  private Optional<Double> wrapValueIntoOptional(double value) {
    if (value == -1.0) {
      return Optional.empty();
    } else {
      return Optional.of(value);
    }
  }

  private void calculateParsingBuckFilesEstimatedProgress() {
    double expectedNumberRules = expectedNumberOfParsedRules.doubleValue();
    double expectedNumberOfBUCKFiles = expectedNumberOfParsedBUCKFiles.doubleValue();

    double newValue;
    if (expectedNumberRules == 0.0 || expectedNumberOfBUCKFiles == 0.0) {
      newValue = -1.0;
    } else {
      double rulesProgress = numberOfParsedRules.doubleValue() / expectedNumberRules;
      double filesProgress = numberOfParsedBUCKFiles.doubleValue() / expectedNumberOfBUCKFiles;
      newValue = Math.min((rulesProgress + filesProgress) / 2.0, 1.0);
      newValue = Math.floor(newValue * 100.0) / 100.0;
    }

    double oldValue = parsingFilesProgress.getAndSet(newValue);
    if (oldValue != newValue) {
      buckEventBus.post(ProgressEvent.parsingProgressUpdated(newValue));
    }
  }

  /**
   * @return Estimated progress of generating projects stage. If return value is absent, it's
   *     impossible to compute the estimated progress.
   */
  public Optional<Double> getEstimatedProgressOfGeneratingProjectFiles() {
    return wrapValueIntoOptional(projectGenerationProgress.get());
  }

  private void calculateProjectFilesGenerationEstimatedProgress() {
    double numberOfProcessedProjectFiles = numberOfGeneratedProjectFiles.doubleValue();
    double expectedNumberOfProjectFiles = expectedNumberOfGeneratedProjectFiles.doubleValue();

    double newValue;
    if (numberOfProcessedProjectFiles == 0.0 || expectedNumberOfProjectFiles == 0.0) {
      newValue = -1.0;
    } else {
      newValue = Math.min((numberOfProcessedProjectFiles / expectedNumberOfProjectFiles), 1.0);
      newValue = Math.floor(newValue * 100.0) / 100.0;
    }
    double oldValue = projectGenerationProgress.getAndSet(newValue);
    if (oldValue != newValue) {
      buckEventBus.post(ProgressEvent.projectGenerationProgressUpdated(newValue));
    }
  }

  /**
   * @return Approximated progress of current build. Returns absent value if number of rules wasn't
   *     determined.
   */
  public Optional<Double> getApproximateBuildProgress() {
    return wrapValueIntoOptional(buildProgress.get());
  }

  private void calculateBuildProgress() {
    double ruleCount = numberOfRules.doubleValue();

    double newValue;
    if (ruleCount == 0.0) {
      newValue = -1.0;
    } else {
      double buildProgress = numberOfFinishedRules.get() / ruleCount;
      newValue = Math.floor(buildProgress * 100.0) / 100.0;
    }

    double oldValue = buildProgress.getAndSet(newValue);
    if (oldValue != newValue) {
      buckEventBus.post(ProgressEvent.buildProgressUpdated(newValue));
    }
  }

  private Future<?> runAsync(Runnable func) {
    if (!isClosed) {
      synchronized (this) {
        if (!isClosed) {
          return executorService.submit(func);
        }
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  private <T> Optional<T> runWithTimeout(Supplier<T> func) {
    try {
      return Optional.of(
          // this runs in the executorService thread, so we cannot use that to schedule this
          // Future as we will deadlock.
          CompletableFuture.supplyAsync(func)
              .get(PROCESS_ESTIMATE_IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      LOG.warn("Timed out operating with Progress Estimator file at %s", storageFile);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      LOG.error("Error executing ProgressEstimation task: %s", e);
    }
    return Optional.empty();
  }

  @Override
  public void close() {
    synchronized (this) {
      isClosed = true;
    }
    executorService.shutdown();
    try {
      executorService.awaitTermination(
          PROCESS_ESTIMATE_IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.warn(
          "Progress Estimator failed to flush all estimation calculations before shutdown. "
              + "Estimated Progress may be incorrect.");
    }
  }
}
