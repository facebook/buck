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

package com.facebook.buck.command;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.exceptions.BuckExecutionException;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@VisibleForTesting
public class BuildReport {
  private static final Logger LOG = Logger.get(BuildReport.class);

  private final BuildExecutionResult buildExecutionResult;
  private final SourcePathResolver pathResolver;

  /**
   * @param buildExecutionResult the build result to generate the report for.
   * @param pathResolver source path resolver which can be used for the result.
   */
  public BuildReport(BuildExecutionResult buildExecutionResult, SourcePathResolver pathResolver) {
    this.buildExecutionResult = buildExecutionResult;
    this.pathResolver = pathResolver;
  }

  public String generateForConsole(Console console) {
    Ansi ansi = console.getAnsi();
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();

    StringBuilder report = new StringBuilder();
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
      }

      String successIndicator;
      String successType;
      SourcePath outputFile;
      if (success.isPresent()) {
        successIndicator = ansi.asHighlightedSuccessText("OK  ");
        successType = success.get().name();
        outputFile = rule.getSourcePathToOutput();
      } else {
        successIndicator = ansi.asHighlightedFailureText("FAIL");
        successType = null;
        outputFile = null;
      }

      report.append(
          String.format(
              "%s %s%s%s\n",
              successIndicator,
              rule.getBuildTarget(),
              successType != null ? " " + successType : "",
              outputFile != null ? " " + pathResolver.getRelativePath(outputFile) : ""));
    }

    if (!buildExecutionResult.getFailures().isEmpty()
        && console.getVerbosity().shouldPrintStandardInformation()) {
      report.append("\n ** Summary of failures encountered during the build **\n");
      for (BuildResult failureResult : buildExecutionResult.getFailures()) {
        Throwable failure = Preconditions.checkNotNull(failureResult.getFailure());
        new ErrorLogger(
                new ErrorLogger.LogImpl() {
                  @Override
                  public void logUserVisible(String message) {
                    report.append(
                        String.format(
                            "Rule %s FAILED because %s.",
                            failureResult.getRule().getFullyQualifiedName(), message));
                  }

                  @Override
                  public void logUserVisibleInternalError(String message) {
                    logUserVisible(message);
                  }

                  @Override
                  public void logVerbose(Throwable e) {
                    LOG.debug(
                        e,
                        "Error encountered while building %s.",
                        failureResult.getRule().getFullyQualifiedName());
                  }
                })
            .setSuppressStackTraces(true)
            .logException(failure);
      }
    }

    return report.toString();
  }

  public String generateJsonBuildReport() throws IOException {
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();
    LinkedHashMap<String, Object> results = new LinkedHashMap<>();
    LinkedHashMap<String, Object> failures = new LinkedHashMap<>();
    boolean isOverallSuccess = true;
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
      }
      Map<String, Object> value = new LinkedHashMap<>();

      boolean isSuccess = success.isPresent();
      value.put("success", isSuccess);
      if (!isSuccess) {
        isOverallSuccess = false;
      }

      if (isSuccess) {
        value.put("type", success.get().name());
        SourcePath outputFile = rule.getSourcePathToOutput();
        value.put(
            "output",
            outputFile != null ? pathResolver.getRelativePath(outputFile).toString() : null);
      }
      results.put(rule.getFullyQualifiedName(), value);
    }

    for (BuildResult failureResult : buildExecutionResult.getFailures()) {
      Throwable failure = Preconditions.checkNotNull(failureResult.getFailure());
      while ((failure instanceof BuckExecutionException
              || failure instanceof ExecutionException
              || failure instanceof BuckUncheckedExecutionException)
          && failure.getCause() != null) {
        failure = failure.getCause();
      }
      failures.put(failureResult.getRule().getFullyQualifiedName(), failure.getMessage());
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("success", isOverallSuccess);
    report.put("results", results);
    report.put("failures", failures);
    return ObjectMappers.WRITER
        .withFeatures(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(report);
  }
}
