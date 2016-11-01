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
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Responsible for gathering logs and other interesting information from buck, driven by user
 * interaction.
 */
public class InteractiveReport extends AbstractReport {

  private final BuildLogHelper buildLogHelper;
  private final Optional<VcsInfoCollector> vcsInfoCollector;
  private final Console console;
  private final UserInput input;
  private final PrintStream output;

  public InteractiveReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      ObjectMapper objectMapper,
      Console console,
      PrintStream output,
      InputStream stdin,
      BuildEnvironmentDescription buildEnvironmentDescription,
      Optional<VcsInfoCollector> vcsInfoCollector,
      RageConfig rageConfig,
      ExtraInfoCollector extraInfoCollector) {
    super(filesystem,
        defectReporter,
        buildEnvironmentDescription,
        output,
        rageConfig,
        extraInfoCollector);
    this.buildLogHelper = new BuildLogHelper(filesystem, objectMapper);
    this.vcsInfoCollector = vcsInfoCollector;
    this.output = output;
    this.console = console;
    this.input = new UserInput(output, new BufferedReader(new InputStreamReader(stdin)));
  }

  @Override
  public ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException {
    ImmutableList<BuildLogEntry> buildLogs = buildLogHelper.getBuildLogs();

    // Commands with unknown args and buck rage should be excluded.
    List<BuildLogEntry> interestingBuildLogs = new ArrayList<>();
    for (BuildLogEntry entry : buildLogs) {
      if (entry.getCommandArgs().isPresent() && !entry.getCommandArgs().get().contains("rage")) {
        interestingBuildLogs.add(entry);
      }
    }
    if (interestingBuildLogs.isEmpty()) {
      return ImmutableSet.of();
    }

    // Sort the interesting builds based on time, reverse order so the most recent is first.
    Collections.sort(
        interestingBuildLogs,
        Ordering.natural().onResultOf(BuildLogEntry::getLastModifiedTime).reverse());

    return input.selectRange(
        "Which buck invocations would you like to report?",
        interestingBuildLogs,
        input1 -> {
          Pair<Double, SizeUnit> humanReadableSize = SizeUnit.getHumanReadableSize(
              input1.getSize(),
              SizeUnit.BYTES);
          return String.format(
              "\t%s\tbuck [%s] %s (%.2f %s)",
              input1.getLastModifiedTime(),
              input1.getCommandArgs().orElse("unknown command"),
              prettyPrintExitCode(input1.getExitCode()),
              humanReadableSize.getFirst(),
              humanReadableSize.getSecond().getAbbreviation());
        });
  }

  @Override
  protected Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException {
    if (!vcsInfoCollector.isPresent() ||
        !input.confirm("Would you like to attach source control information (this includes " +
            "information about commits and changed files)?")) {
      return Optional.empty();
    }

    try {
      return Optional.of(vcsInfoCollector.get().gatherScmInformation());
    } catch (VersionControlCommandFailedException e) {
      output.printf("Failed to get source control information: %s, proceeding regardless.\n", e);
    }
    return Optional.empty();
  }

  private String prettyPrintExitCode(OptionalInt exitCode) {
    String result = "Exit code: " +
        (exitCode.isPresent() ? Integer.toString(exitCode.getAsInt()) : "Unknown");
    if (exitCode.isPresent() && console.getAnsi().isAnsiTerminal()) {
      if (exitCode.getAsInt() == 0) {
        return console.getAnsi().asGreenText(result);
      } else {
        return console.getAnsi().asRedText(result);
      }
    }
    return result;
  }

  @Override
  protected Optional<UserReport> getUserReport() throws IOException {
    UserReport.Builder userReport = UserReport.builder();

    userReport.setUserIssueDescription(
        input.ask("Please describe the problem you wish to report:"));

    return Optional.of(userReport.build());
  }

}
