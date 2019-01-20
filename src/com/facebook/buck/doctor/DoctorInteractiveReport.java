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

package com.facebook.buck.doctor;

import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

/**
 * Responsible for gathering logs and other interesting information from buck when part of the
 * information is already available when calling the constructor.
 */
public class DoctorInteractiveReport extends AbstractReport {

  private final ImmutableSet<BuildLogEntry> buildLogEntries;
  private final UserInput input;
  private final Optional<String> issueDescription;

  public DoctorInteractiveReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      Console console,
      UserInput input,
      Optional<String> issueDescription,
      BuildEnvironmentDescription buildEnvironmentDescription,
      VersionControlStatsGenerator versionControlStatsGenerator,
      DoctorConfig doctorConfig,
      ExtraInfoCollector extraInfoCollector,
      ImmutableSet<BuildLogEntry> buildLogEntries,
      Optional<WatchmanDiagReportCollector> watchmanDiagReportCollector) {
    super(
        filesystem,
        defectReporter,
        buildEnvironmentDescription,
        versionControlStatsGenerator,
        console,
        doctorConfig,
        extraInfoCollector,
        watchmanDiagReportCollector);
    this.input = input;
    this.issueDescription = issueDescription;
    this.buildLogEntries = buildLogEntries;
  }

  @Override
  public ImmutableSet<BuildLogEntry> promptForBuildSelection() {
    return buildLogEntries;
  }

  @Override
  protected Optional<FileChangesIgnoredReport> getFileChangesIgnoredReport()
      throws IOException, InterruptedException {
    return runWatchmanDiagReportCollector(input);
  }

  @Override
  protected Optional<UserReport> getUserReport() {
    return issueDescription.map(issue -> UserReport.of("", issue));
  }
}
