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
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Responsible for gathering logs and other interesting information from buck, driven by user
 * interaction.
 */
public class InteractiveReport extends AbstractReport {

  private static final int ARGS_MAX_CHARS = 60;

  private final BuildLogHelper buildLogHelper;
  private final Console console;
  private final UserInput input;

  public InteractiveReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      Console console,
      InputStream stdin,
      BuildEnvironmentDescription buildEnvironmentDescription,
      VersionControlStatsGenerator versionControlStatsGenerator,
      RageConfig rageConfig,
      ExtraInfoCollector extraInfoCollector,
      Optional<WatchmanDiagReportCollector> watchmanDiagReportCollector) {
    super(
        filesystem,
        defectReporter,
        buildEnvironmentDescription,
        versionControlStatsGenerator,
        console,
        rageConfig,
        extraInfoCollector,
        watchmanDiagReportCollector);
    this.buildLogHelper = new BuildLogHelper(filesystem);
    this.console = console;
    this.input =
        new UserInput(console.getStdOut(), new BufferedReader(new InputStreamReader(stdin)));
  }

  @Override
  public ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException {
    ImmutableList<BuildLogEntry> buildLogs = buildLogHelper.getBuildLogs();
    if (buildLogs.isEmpty()) {
      return ImmutableSet.of();
    }

    return input.selectRange(
        "Which buck invocations would you like to report?",
        buildLogs,
        entry -> {
          Pair<Double, SizeUnit> humanReadableSize =
              SizeUnit.getHumanReadableSize(entry.getSize(), SizeUnit.BYTES);
          String cmdArgs = entry.getCommandArgs().orElse("unknown command");
          cmdArgs = cmdArgs.substring(0, Math.min(cmdArgs.length(), ARGS_MAX_CHARS));

          return String.format(
              "\t%s\tbuck [%s] %s (%.2f %s)",
              entry.getLastModifiedTime(),
              cmdArgs,
              prettyPrintExitCode(entry.getExitCode()),
              humanReadableSize.getFirst(),
              humanReadableSize.getSecond().getAbbreviation());
        });
  }

  @Override
  protected Optional<FileChangesIgnoredReport> getFileChangesIgnoredReport()
      throws IOException, InterruptedException {
    return runWatchmanDiagReportCollector(input);
  }

  @Override
  protected Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException {
    if (!input.confirm(
        "Would you like to attach source control information (this includes "
            + "information about commits and changed files)?")) {
      return Optional.empty();
    }
    return super.getSourceControlInfo();
  }

  private String prettyPrintExitCode(OptionalInt exitCode) {
    String result =
        "Exit code: " + (exitCode.isPresent() ? Integer.toString(exitCode.getAsInt()) : "Unknown");
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
