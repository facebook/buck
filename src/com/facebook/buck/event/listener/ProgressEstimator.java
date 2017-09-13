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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ProgressEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AtomicDouble;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class ProgressEstimator {

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

  public ProgressEstimator(Path storageFile, BuckEventBus buckEventBus) {
    this.storageFile = storageFile;
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
    calculateParsingBuckFilesEstimatedProgress();
  }

  public void didFinishParsing() {
    if (command != null) {
      expectedNumberOfParsedRules.set(numberOfParsedRules.get());
      expectedNumberOfParsedBUCKFiles.set(numberOfParsedBUCKFiles.get());
      calculateParsingBuckFilesEstimatedProgress();
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

  private void fillEstimationsForCommand(String aCommand) {
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
  }

  private void preloadEstimationsFromStorageFile() {
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
    expectationsStorage = map;
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
    Map<String, Number> commandEstimations = getEstimationsForCommand(aCommand);
    if (commandEstimations != null) {
      commandEstimations.put(EXPECTED_NUMBER_OF_PARSED_RULES, numberOfParsedRules);
      commandEstimations.put(EXPECTED_NUMBER_OF_PARSED_BUCK_FILES, numberOfParsedBUCKFiles);
      saveEstimatedValues();
    }
  }

  private void saveEstimatedValues() {
    if (expectationsStorage == null) {
      return;
    }
    try {
      Files.createDirectories(storageFile.getParent());
    } catch (IOException e) {
      LOG.warn(
          "Unable to make path for storage %s: %s",
          storageFile.toString(), e.getLocalizedMessage());
      return;
    }
    try {
      ObjectMappers.WRITER.writeValue(storageFile.toFile(), expectationsStorage);
    } catch (IOException e) {
      LOG.warn("Unable to save progress expectations: " + e.getLocalizedMessage());
    }
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
}
