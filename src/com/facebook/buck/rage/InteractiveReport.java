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
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * Responsible for gathering logs and other interesting information from buck, driven by user
 * interaction.
 */
public class InteractiveReport extends AbstractReport {

  private final BuildLogHelper buildLogHelper;
  private final Optional<VcsInfoCollector> vcsInfoCollector;
  private final UserInput input;

  public InteractiveReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      PrintStream output,
      InputStream stdin,
      BuildEnvironmentDescription buildEnvironmentDescription,
      Optional<VcsInfoCollector> vcsInfoCollector) {
    super(
        defectReporter,
        buildEnvironmentDescription,
        output);
    this.buildLogHelper = new BuildLogHelper(filesystem);
    this.vcsInfoCollector = vcsInfoCollector;
    this.input = new UserInput(output, new BufferedReader(new InputStreamReader(stdin)));
  }

  @Override
  protected ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException {
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

  @Override
  protected Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException {
    if (!vcsInfoCollector.isPresent() ||
        !input.confirm("Would you like to attach source control information (this includes " +
            "information about commits and changed files)?")) {
      return Optional.absent();
    }

    try {
      return Optional.of(vcsInfoCollector.get().gatherScmInformation());
    } catch (VersionControlCommandFailedException e) {
      output.printf("Failed to get source control information: %s, proceeding regardless.\n", e);
    }
    return Optional.absent();
  }

  @Override
  protected Optional<UserReport> getUserReport() throws IOException {
    UserReport.Builder userReport = UserReport.builder();

    userReport.setUserIssueDescription(
        input.ask("Please describe the problem you wish to report:"));

    return Optional.of(userReport.build());
  }

}
