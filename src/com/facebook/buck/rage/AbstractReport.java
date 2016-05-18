/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rage;

import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Base class for gathering logs and other interesting information from buck.
 */
public abstract class AbstractReport {

  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final PrintStream output;
  private final ExtraInfoCollector extraInfoCollector;

  public AbstractReport(
      DefectReporter defectReporter,
      BuildEnvironmentDescription buildEnvironmentDescription,
      PrintStream output,
      ExtraInfoCollector extraInfoCollector) {
    this.defectReporter = defectReporter;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.output = output;
    this.extraInfoCollector = extraInfoCollector;
  }

  protected abstract ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException;
  protected abstract Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException;
  protected abstract Optional<UserReport> getUserReport() throws IOException;

  public final DefectSubmitResult collectAndSubmitResult()
      throws IOException, InterruptedException {

    Optional<UserReport> userReport = getUserReport();
    ImmutableSet<BuildLogEntry> highlightedBuilds = promptForBuildSelection();
    Optional<SourceControlInfo> sourceControlInfo = getSourceControlInfo();

    ImmutableSet<Path> extraInfoPaths = ImmutableSet.of();
    Optional<String> extraInfo = Optional.absent();
    try {
      Optional<ExtraInfoResult> extraInfoResultOptional = extraInfoCollector.run();
      if (extraInfoResultOptional.isPresent()) {
        extraInfoPaths = extraInfoResultOptional.get().getExtraFiles();
        extraInfo = Optional.of(extraInfoResultOptional.get().getOutput());
      }
    } catch (DefaultExtraInfoCollector.ExtraInfoExecutionException e) {
      output.printf("There was a problem gathering additional information: %s. " +
          "The results will not be attached to the report.", e.getMessage());
    }

    ImmutableSet<Path> includedPaths = FluentIterable.from(highlightedBuilds)
        .transform(
            new Function<BuildLogEntry, Path>() {
              @Override
              public Path apply(BuildLogEntry input) {
                return input.getRelativePath();
              }
            })
        .append(extraInfoPaths)
        .toSet();

    DefectReport defectReport = DefectReport.builder()
        .setUserReport(userReport)
        .setHighlightedBuildIds(
            FluentIterable.from(highlightedBuilds)
                .transformAndConcat(
                    new Function<BuildLogEntry, Iterable<BuildId>>() {
                      @Override
                      public Iterable<BuildId> apply(BuildLogEntry input) {
                        return input.getBuildId().asSet();
                      }
                    }))
        .setBuildEnvironmentDescription(buildEnvironmentDescription)
        .setSourceControlInfo(sourceControlInfo)
        .setIncludedPaths(includedPaths)
        .setExtraInfo(extraInfo)
        .build();

    output.println("Writing report, please wait..");
    return defectReporter.submitReport(defectReport);
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractUserReport {
    String getUserIssueDescription();
  }

}
