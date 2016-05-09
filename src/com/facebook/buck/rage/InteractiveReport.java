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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Responsible for gathering logs and other interesting information from buck, driven by user
 * interaction.
 */
public class InteractiveReport {

  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final BuildLogHelper buildLogHelper;
  private final PrintStream output;
  private final Optional<VcsInfoCollector> vcsInfoHelper;
  private final UserInput input;

  public InteractiveReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      PrintStream output,
      InputStream stdin,
      BuildEnvironmentDescription buildEnvironmentDescription,
      Optional<VcsInfoCollector> vcsInfoHelper) {
    this.defectReporter = defectReporter;
    this.output = output;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.buildLogHelper = new BuildLogHelper(filesystem);
    this.vcsInfoHelper = vcsInfoHelper;
    this.input = new UserInput(output, new BufferedReader(new InputStreamReader(stdin)));
  }

  private ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException {
    ImmutableList<BuildLogEntry> buildLogs = buildLogHelper.getBuildLogs();
    return input.selectRange(
        "Which buck invocations would you like to report?",
        buildLogs,
        new Function<BuildLogEntry, String>() {
          @Override
          public String apply(BuildLogEntry input) {
            Pair<Double, SizeUnit> humanReadableSize = SizeUnit.getHumanReadableSize(
                input.getSize(),
                SizeUnit.BYTES);
            return String.format(
                "buck [%s] at %s (%.2f %s)",
                input.getCommandArgs().or("unknown args"),
                input.getLastModifiedTime(),
                humanReadableSize.getFirst(),
                humanReadableSize.getSecond().getAbbreviation());
          }
        });
  }

  public DefectSubmitResult collectAndSubmitResult() throws IOException, InterruptedException {
    UserReport.Builder userReport = UserReport.builder();

    userReport.setUserIssueDescription(
        input.ask("Please describe the problem you wish to report:"));

    ImmutableSet<BuildLogEntry> hightlghtedBuilds = promptForBuildSelection();
    ImmutableSet.Builder<Path> logsAndTraces = ImmutableSet.builder();
    for (BuildLogEntry hightlghtedBuild : hightlghtedBuilds) {
      logsAndTraces.add(hightlghtedBuild.getRelativePath());
    }

    Optional<SourceControlInfo> sourceControlInfo = Optional.absent();
    if (vcsInfoHelper.isPresent() &&
        input.confirm("Would you like to attach source control information (this includes " +
        "information about commits and changed files)?")) {
      try {
        sourceControlInfo = Optional.of(vcsInfoHelper.get().gatherScmInformation());
      } catch (VersionControlCommandFailedException e) {
        output.printf("Failed to get source control information: %s, proceeding regardless.\n", e);
      }
    }

    DefectReport defectReport = DefectReport.builder()
        .setUserReport(userReport.build())
        .setHighlightedBuildIds(
            FluentIterable.from(hightlghtedBuilds)
                .transformAndConcat(
                    new Function<BuildLogEntry, Iterable<BuildId>>() {
                      @Override
                      public Iterable<BuildId> apply(BuildLogEntry input) {
                        return input.getBuildId().asSet();
                      }
                    }))
        .setBuildEnvironmentDescription(buildEnvironmentDescription)
        .setSourceControlInfo(sourceControlInfo)
        .setIncludedPaths(
            FluentIterable.from(hightlghtedBuilds)
                .transform(
                    new Function<BuildLogEntry, Path>() {
                      @Override
                      public Path apply(BuildLogEntry input) {
                        return input.getRelativePath();
                      }
                    })
                .toSet())
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
