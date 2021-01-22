/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.command;

import static com.facebook.buck.util.string.MoreStrings.linesToText;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

@VisibleForTesting
public class BuildReport {
  private static final Logger LOG = Logger.get(BuildReport.class);

  private final Build.BuildExecutionResult buildExecutionResult;
  private final SourcePathResolverAdapter pathResolver;
  private final Cells cells;
  private final int maxNumberOfEntries;

  /**
   * @param buildExecutionResult the build result to generate the report for.
   * @param pathResolver source path resolver which can be used for the result.
   * @param cells
   * @param maxNumberOfEntries
   */
  public BuildReport(
      Build.BuildExecutionResult buildExecutionResult,
      SourcePathResolverAdapter pathResolver,
      Cells cells,
      int maxNumberOfEntries) {
    this.buildExecutionResult = buildExecutionResult;
    this.pathResolver = pathResolver;
    this.cells = cells;
    this.maxNumberOfEntries = maxNumberOfEntries;
  }

  public String generateForConsole(Console console) {
    Ansi ansi = console.getAnsi();
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();

    StringBuilder report = new StringBuilder();
    int numEntries = 0;
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      if (numEntries == maxNumberOfEntries) {
        report.append(
            String.format("\tTruncated %s rule(s)...%n", ruleToResult.size() - maxNumberOfEntries));
        break;
      }
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();

      String successIndicator;
      String successType;
      @Nullable ImmutableMap<OutputLabel, ImmutableSet<Path>> outputPathsByLabels;
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
        if (success.isPresent()) {
          successIndicator = ansi.asHighlightedSuccessText("OK  ");
          successType = success.get().name();
          outputPathsByLabels = getMultipleOutputPaths(rule);
        } else {
          successIndicator = ansi.asHighlightedFailureText("FAIL");
          successType = null;
          outputPathsByLabels = null;
        }
      } else {
        successIndicator = ansi.asHighlightedFailureText("UNKNOWN");
        successType = null;
        outputPathsByLabels = null;
      }

      if (outputPathsByLabels == null) {
        report.append(
            getTargetReport(
                BuildTargetWithOutputs.of(rule.getBuildTarget(), OutputLabel.defaultLabel()),
                successIndicator,
                successType,
                null));
      } else {
        for (Map.Entry<OutputLabel, ImmutableSet<Path>> labelToPaths :
            outputPathsByLabels.entrySet()) {
          OutputLabel label = labelToPaths.getKey();
          for (Path path : labelToPaths.getValue()) {
            report.append(
                getTargetReport(
                    BuildTargetWithOutputs.of(rule.getBuildTarget(), label),
                    successIndicator,
                    successType,
                    path));
          }
        }
      }
      numEntries++;
    }

    numEntries = 0;
    if (!buildExecutionResult.getFailures().isEmpty()
        && console.getVerbosity().shouldPrintStandardInformation()) {
      report.append(linesToText("", " ** Summary of failures encountered during the build **", ""));
      for (BuildResult failureResult : buildExecutionResult.getFailures()) {
        if (numEntries == maxNumberOfEntries) {
          report.append(System.lineSeparator());
          report.append(
              String.format(
                  "\tTruncated %s failure(s)...%n",
                  buildExecutionResult.getFailures().size() - maxNumberOfEntries));
          break;
        }
        Throwable failure = Objects.requireNonNull(failureResult.getFailure());
        new ErrorLogger(
                new ErrorLogger.LogImpl() {
                  @Override
                  public void logUserVisible(String message) {
                    report.append(
                        String.format(
                            "Rule %s FAILED because %s.%n",
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
                },
                new HumanReadableExceptionAugmentor(ImmutableMap.of()))
            .logException(failure);

        numEntries++;
      }
    }

    return report.toString();
  }

  private String getTargetReport(
      BuildTargetWithOutputs target,
      String successIndicator,
      @Nullable String successType,
      @Nullable Path outputPath) {
    return String.format(
        "%s %s%s%s%s",
        successIndicator,
        target,
        successType != null ? " " + successType : "",
        outputPath != null ? " " + outputPath : "",
        System.lineSeparator());
  }

  public String generateJsonBuildReport() throws IOException {
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();
    LinkedHashMap<String, Object> results = new LinkedHashMap<>();
    LinkedHashMap<String, Object> failures = new LinkedHashMap<>();
    int numEntriesWritten = 0;

    boolean isOverallSuccess = true;
    boolean truncated = false;
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
      }

      boolean isSuccess = success.isPresent();
      if (!isSuccess) {
        isOverallSuccess = false;
      }

      if (numEntriesWritten == maxNumberOfEntries) {
        truncated = true;
        break;
      }

      Map<String, Object> value = new LinkedHashMap<>();
      value.put("success", result.map(r -> r.getStatus().toString()).orElse("UNKNOWN"));
      if (isSuccess) {
        value.put("type", success.get().name());
        value.put("outputs", getMultipleOutputPaths(rule));
      }
      results.put(rule.getFullyQualifiedName(), value);
      numEntriesWritten++;
    }

    numEntriesWritten = 0;
    for (BuildResult failureResult : buildExecutionResult.getFailures()) {
      if (numEntriesWritten == maxNumberOfEntries) {
        truncated = true;
        break;
      }
      Throwable failure = Objects.requireNonNull(failureResult.getFailure());
      StringBuilder messageBuilder = new StringBuilder();
      new ErrorLogger(
              new LogImpl() {
                @Override
                public void logUserVisible(String message) {
                  messageBuilder.append(message);
                }

                @Override
                public void logUserVisibleInternalError(String message) {
                  messageBuilder.append(message);
                }

                @Override
                public void logVerbose(Throwable e) {}
              },
              new HumanReadableExceptionAugmentor(ImmutableMap.of()))
          .logException(failure);
      failures.put(failureResult.getRule().getFullyQualifiedName(), messageBuilder.toString());
      numEntriesWritten++;
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("success", isOverallSuccess);
    report.put("results", results);
    report.put("failures", failures);
    report.put("truncated", truncated);
    return ObjectMappers.WRITER
        .withFeatures(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(report);
  }

  /**
   * Returns a set of paths representing all outputs generated by the given {@code rule}, or null if
   * no outputs are available.
   *
   * <p>For rules that do not provide multiple outputs, the return value is null or a set of one
   * output. For rules with multiple outputs, the rule will provide at least the default output
   * group, so the return value is a set of zero or more outputs. Note that zero outputs in an
   * output group is valid.
   */
  @Nullable
  private ImmutableMap<OutputLabel, ImmutableSet<Path>> getMultipleOutputPaths(BuildRule rule) {
    if (rule instanceof HasMultipleOutputs) {
      HasMultipleOutputs multipleOutputsRule = (HasMultipleOutputs) rule;
      ProjectFilesystem projectFilesystem = rule.getProjectFilesystem();
      ImmutableSet<OutputLabel> outputLabels = multipleOutputsRule.getOutputLabels();
      ImmutableMap.Builder<OutputLabel, ImmutableSet<Path>> allPathsBuilder =
          ImmutableMap.builderWithExpectedSize(outputLabels.size());
      for (OutputLabel outputLabel : outputLabels) {
        ImmutableSortedSet<SourcePath> sourcePaths =
            multipleOutputsRule.getSourcePathToOutput(outputLabel);
        ImmutableSet.Builder<Path> pathBuilderForLabel =
            ImmutableSet.builderWithExpectedSize(sourcePaths.size());
        for (SourcePath sourcePath : sourcePaths) {
          pathBuilderForLabel.add(
              relativizeSourcePathToProjectRoot(projectFilesystem, sourcePath).getPath());
        }
        allPathsBuilder.put(outputLabel, pathBuilderForLabel.build());
      }
      return allPathsBuilder.build();
    }
    Path output = getRuleOutputPath(rule);
    if (output == null) {
      return null;
    }
    return ImmutableMap.of(OutputLabel.defaultLabel(), ImmutableSet.of(output));
  }

  @Nullable
  private Path getRuleOutputPath(BuildRule rule) {
    SourcePath outputFile = rule.getSourcePathToOutput();
    if (outputFile == null) {
      return null;
    }
    return relativizeSourcePathToProjectRoot(rule.getProjectFilesystem(), outputFile).getPath();
  }

  private RelPath relativizeSourcePathToProjectRoot(
      ProjectFilesystem projectFilesystem, SourcePath sourcePath) {
    RelPath relativeOutputPath = pathResolver.getCellUnsafeRelPath(sourcePath);
    AbsPath absoluteOutputPath = projectFilesystem.resolve(relativeOutputPath);
    return cells.getRootCell().getFilesystem().relativize(absoluteOutputPath);
  }
}
