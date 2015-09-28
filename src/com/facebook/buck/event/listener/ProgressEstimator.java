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

import com.facebook.buck.cli.ProgressEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.BuckConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AtomicDouble;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

public class ProgressEstimator {

  private final Path rootRepositoryPath;

  private static final Logger LOG = Logger.get(ProgressEstimator.class);

  public static final String EXPECTED_NUMBER_OF_PARSED_RULES = "expectedNumberOfParsedRules";
  public static final String EXPECTED_NUMBER_OF_PARSED_BUCK_FILES =
      "expectedNumberOfParsedBUCKFiles";
  public static final String EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES =
      "expectedNumberOfGeneratedProjectFiles";
  public static final String PROGRESS_ESTIMATIONS_JSON = ".progressestimations.json";

  @Nullable
  private String command;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private BuckEventBus buckEventBus;

  @Nullable
  private Map<String, Map<String, Number>> expectationsStorage;

  private final AtomicInteger numberOfParsedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfParsedBUCKFiles = new AtomicInteger(0);
  private final AtomicInteger numberOfGeneratedProjectFiles = new AtomicInteger(0);

  private final AtomicInteger expectedNumberOfParsedRules = new AtomicInteger(0);
  private final AtomicInteger expectedNumberOfParsedBUCKFiles = new AtomicInteger(0);
  private final AtomicInteger expectedNumberOfGeneratedProjectFiles = new AtomicInteger(0);

  private final AtomicInteger numberOfRules = new AtomicInteger(0);
  private final AtomicInteger numberOfStartedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfResumedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfSuspendedRules = new AtomicInteger(0);
  private final AtomicInteger numberOfFinishedRules = new AtomicInteger(0);

  private final AtomicDouble processingFilesProgress = new AtomicDouble(-1.0);
  private final AtomicDouble projectGenerationProgress = new AtomicDouble(-1.0);
  private final AtomicDouble buildProgress = new AtomicDouble(-1.0);

  public ProgressEstimator(Path rootRepositoryPath, BuckEventBus buckEventBus) {
    this.rootRepositoryPath = rootRepositoryPath;
    this.command = null;
    this.buckEventBus = buckEventBus;
    this.expectationsStorage = null;
  }

  public void setCurrentCommand(String commandName, ImmutableList<String> commandArgs) {
    command = commandName + " " + Joiner.on(" ").join(commandArgs);
    fillEstimationsForCommand(command);
  }

  public void didParseBuckRules(int amount) {
    numberOfParsedRules.addAndGet(amount);
    numberOfParsedBUCKFiles.incrementAndGet();
    calculateProcessingFilesEstimatedProgress();
  }

  public void didFinishParsing() {
    if (command != null) {
      expectedNumberOfParsedRules.set(numberOfParsedRules.get());
      expectedNumberOfParsedBUCKFiles.set(numberOfParsedBUCKFiles.get());
      calculateProcessingFilesEstimatedProgress();
      updateEstimatedBuckFilesParsingValues(command);
    }
  }

  public void didGenerateProjectForTarget() {
    numberOfGeneratedProjectFiles.incrementAndGet();
    calculateProjectFilesGenerationEstimatedProgress();
  }

  public void didFinishProjectGeneration() {
    if (command != null) {
      expectedNumberOfGeneratedProjectFiles.set(numberOfGeneratedProjectFiles.get());
      calculateProjectFilesGenerationEstimatedProgress();
      updateEstimatedProjectGenerationValues(command);
    }
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
    numberOfResumedRules.incrementAndGet();
    calculateBuildProgress();
  }

  public void didSuspendRule() {
    numberOfSuspendedRules.incrementAndGet();
    calculateBuildProgress();
  }

  public void didFinishRule() {
    numberOfFinishedRules.incrementAndGet();
    calculateBuildProgress();
  }

  public void didStartBuild() {
    numberOfStartedRules.set(0);
    numberOfFinishedRules.set(0);
    numberOfResumedRules.set(0);
    numberOfSuspendedRules.set(0);
  }

  public void didFinishBuild() {
    int rulesCount = numberOfRules.intValue();
    if (rulesCount > 0) {
      numberOfStartedRules.set(rulesCount);
      numberOfFinishedRules.set(rulesCount);
      numberOfSuspendedRules.set(rulesCount * 2);
      numberOfResumedRules.set(rulesCount * 2);
      calculateBuildProgress();
    }
  }

  private Path getStorageFilePath() {
    return rootRepositoryPath
        .resolve(BuckConstant.BUCK_OUTPUT_PATH)
        .resolve(PROGRESS_ESTIMATIONS_JSON);
  }

  private void fillEstimationsForCommand(String aCommand) {
    preloadEstimationsFromStorageFile();
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      if (commandEstimations.containsKey(EXPECTED_NUMBER_OF_PARSED_RULES) &&
          commandEstimations.containsKey(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES)) {
        expectedNumberOfParsedRules.set(
            commandEstimations.get(EXPECTED_NUMBER_OF_PARSED_RULES).intValue());
        expectedNumberOfParsedBUCKFiles.set(
            commandEstimations.get(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES).intValue());
      }
      if (commandEstimations.containsKey(EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES)) {
        expectedNumberOfGeneratedProjectFiles.set(
            commandEstimations.get(EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES).intValue());
      }
      calculateProcessingFilesEstimatedProgress();
    }
  }

  private void preloadEstimationsFromStorageFile() {
    Path fileWithExpectations = getStorageFilePath();

    Map<String, Map<String, Number>> map = null;
    if (Files.exists(fileWithExpectations)) {
      try {
        byte[] bytes = Files.readAllBytes(fileWithExpectations);
        map = objectMapper.readValue(
            bytes,
            new TypeReference<HashMap<String, Map<String, Number>>>(){});
      } catch (Exception e) {
        LOG.warn("Unable to load progress estimations from file: " + e.getMessage());
      }
    }
    if (map == null) {
      map = new HashMap<String, Map<String, Number>>();
    }
    expectationsStorage = map;
  }

  @Nullable
  private Map<String, Number> getEstimationsForCommand(String aCommand) {
    if (expectationsStorage == null || aCommand == null) {
      return null;
    }
    Map<String, Number> commandEstimations = expectationsStorage.get(aCommand);
    if (commandEstimations == null) {
      commandEstimations = new HashMap<String, Number>();
      expectationsStorage.put(aCommand, commandEstimations);
    }
    return commandEstimations;
  }

  private void updateEstimatedProjectGenerationValues(String aCommand) {
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      commandEstimations.put(
          EXPECTED_NUMBER_OF_GENERATED_PROJECT_FILES,
          numberOfGeneratedProjectFiles);
      saveEstimatedValues();
    }
  }

  private void updateEstimatedBuckFilesParsingValues(String aCommand) {
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      commandEstimations.put(
          EXPECTED_NUMBER_OF_PARSED_RULES,
          numberOfParsedRules);
      commandEstimations.put(
          EXPECTED_NUMBER_OF_PARSED_BUCK_FILES,
          numberOfParsedBUCKFiles);
      saveEstimatedValues();
    }
  }

  private void saveEstimatedValues() {
    if (expectationsStorage == null) {
      return;
    }
    Path storageFilePath = getStorageFilePath();
    try {
      Files.createDirectories(storageFilePath.getParent());
    } catch (IOException e) {
      LOG.warn("Unable to make path for storage %s: %s",
          storageFilePath.toString(),
          e.getLocalizedMessage());
      return;
    }
    try {
      objectMapper.writeValue(storageFilePath.toFile(), expectationsStorage);
    } catch (IOException e) {
      LOG.warn("Unable to save progress expectations: " + e.getLocalizedMessage());
    }
  }

  /**
   * @return Estimated progress of processing files stage. If return value is absent,
   * it's impossible to compute the estimated progress.
   */
  public Optional<Double> getEstimatedProgressOfProcessingBuckFiles() {
    return wrapValueIntoOptional(processingFilesProgress.get());
  }

  private Optional<Double> wrapValueIntoOptional(double value) {
    if (value == -1.0) {
      return Optional.<Double>absent();
    } else {
      return Optional.<Double>of(Double.valueOf(value));
    }
  }

  private void calculateProcessingFilesEstimatedProgress() {
    double expectedNumberRules = expectedNumberOfParsedRules.doubleValue();
    double expectedNumberOfBUCKFiles = expectedNumberOfParsedBUCKFiles.doubleValue();

    double newValue;
    if (expectedNumberRules == 0.0 || expectedNumberOfBUCKFiles == 0.0) {
      newValue = -1.0;
    } else {
      double rulesProgress = numberOfParsedRules.doubleValue() /
          expectedNumberRules;
      double filesProgress = numberOfParsedBUCKFiles.doubleValue() /
          expectedNumberOfBUCKFiles;
      newValue = Math.min((rulesProgress + filesProgress) / 2.0, 1.0);
      newValue = Math.floor(newValue * 100.0) / 100.0;
    }

    double oldValue = processingFilesProgress.getAndSet(newValue);
    if (oldValue != newValue) {
      buckEventBus.post(ProgressEvent.parsingProgressUpdated(newValue));
    }
  }

  /**
   * @return Estimated progress of generating projects stage. If return value is absent,
   * it's impossible to compute the estimated progress.
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
   * @return Approximated progress of current build.
   * Returns absent value if number of rules wasn't determined.
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
      // TODO(beefon): t8529466 compute progress in better way
      double cacheCheckProgress = numberOfStartedRules.get() / ruleCount;
      double buildProgress = (numberOfFinishedRules.get() +
          numberOfSuspendedRules.get() / 2.0 +
          numberOfResumedRules.get() / 2.0) / 3.0 / ruleCount;
      // cache check takes approximately 10% of time on clean builds. If there will be nothing
      // to build after that, we will jump to 100%.
      double totalProgress = cacheCheckProgress * 0.1 + Math.pow(buildProgress, 2.0) * 0.9;
      newValue = Math.floor(totalProgress * 100.0) / 100.0;
    }

    double oldValue = buildProgress.getAndSet(newValue);
    if (oldValue != newValue) {
      buckEventBus.post(ProgressEvent.buildProgressUpdated(newValue));
    }
  }
}
