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
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.support.build.report.BuildReportConfig;
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
import com.google.common.collect.Iterables;
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
  private final Cell rootCell;
  private final boolean removeOutput;

  /**
   * @param buildExecutionResult the build result to generate the report for.
   * @param pathResolver source path resolver which can be used for the result.
   */
  public BuildReport(
      Build.BuildExecutionResult buildExecutionResult,
      SourcePathResolverAdapter pathResolver,
      Cell rootCell) {
    this.buildExecutionResult = buildExecutionResult;
    this.pathResolver = pathResolver;
    this.rootCell = rootCell;
    this.removeOutput = rootCell.getBuckConfig().getView(BuildReportConfig.class).getRemoveOutput();
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
      @Nullable ImmutableMap<OutputLabel, ImmutableSet<Path>> outputPathsByLabels;
      if (success.isPresent()) {
        successIndicator = ansi.asHighlightedSuccessText("OK  ");
        successType = success.get().name();
        outputPathsByLabels = getMultipleOutputPaths(rule);
      } else {
        successIndicator = ansi.asHighlightedFailureText("FAIL");
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
    }

    if (!buildExecutionResult.getFailures().isEmpty()
        && console.getVerbosity().shouldPrintStandardInformation()) {
      report.append(linesToText("", " ** Summary of failures encountered during the build **", ""));
      for (BuildResult failureResult : buildExecutionResult.getFailures()) {
        Throwable failure = Objects.requireNonNull(failureResult.getFailure());
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
                },
                new HumanReadableExceptionAugmentor(ImmutableMap.of()))
            .logException(failure);
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
      value.put("success", result.map(r -> r.getStatus().toString()).orElse("UNKNOWN"));
      if (!isSuccess) {
        isOverallSuccess = false;
      }

      if (isSuccess) {
        value.put("type", success.get().name());
        // Put both "output" and "outputs" into the build report for backwards compatibility.
        // TODO(irenewchen): Remove "output" after existing parsing uses are removed outside Buck
        // code base.
        if (!removeOutput) {
          value.put("output", getDefaultOutputPath(rule));
        }
        value.put("outputs", getMultipleOutputPaths(rule));
      }
      results.put(rule.getFullyQualifiedName(), value);
    }

    for (BuildResult failureResult : buildExecutionResult.getFailures()) {
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
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("success", isOverallSuccess);
    report.put("results", results);
    report.put("failures", failures);
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

  /**
   * Returns a path representing a single default output generated by the given {@code rule}, or
   * null if no such output exists.
   *
   * <p>TODO(irenewchen): Remove this method after removing "output" field in JSON build report
   */
  @Nullable
  private Path getDefaultOutputPath(BuildRule rule) {
    if (rule instanceof HasMultipleOutputs) {
      ImmutableSet<SourcePath> defaultPaths =
          ((HasMultipleOutputs) rule).getSourcePathToOutput(OutputLabel.defaultLabel());
      if (defaultPaths != null && defaultPaths.size() == 1) {
        return relativizeSourcePathToProjectRoot(
                rule.getProjectFilesystem(), Iterables.getOnlyElement(defaultPaths))
            .getPath();
      }
      return null;
    }
    return getRuleOutputPath(rule);
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
    Path relativeOutputPath = pathResolver.getRelativePath(sourcePath);
    Path absoluteOutputPath = projectFilesystem.resolve(relativeOutputPath);
    return rootCell.getFilesystem().relativize(absoluteOutputPath);
  }
}
