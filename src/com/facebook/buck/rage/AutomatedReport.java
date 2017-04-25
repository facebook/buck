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
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

/**
 * Responsible for gathering logs and other interesting information from buck without user
 * interaction.
 */
public class AutomatedReport extends AbstractReport {
  private final BuildLogHelper buildLogHelper;
  private final boolean gatherVcsInfo;

  public AutomatedReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      Console console,
      BuildEnvironmentDescription buildEnvironmentDescription,
      VersionControlStatsGenerator versionControlStatsGenerator,
      boolean gatherVcsInfo,
      RageConfig rageConfig,
      ExtraInfoCollector extraInfoCollector) {
    super(
        filesystem,
        defectReporter,
        buildEnvironmentDescription,
        versionControlStatsGenerator,
        console,
        rageConfig,
        extraInfoCollector,
        Optional.empty());
    this.buildLogHelper = new BuildLogHelper(filesystem);
    this.gatherVcsInfo = gatherVcsInfo;
  }

  @Override
  public ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException {
    return ImmutableSet.copyOf(buildLogHelper.getBuildLogs());
  }

  @Override
  protected Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException {
    return gatherVcsInfo ? super.getSourceControlInfo() : Optional.empty();
  }

  @Override
  protected Optional<FileChangesIgnoredReport> getFileChangesIgnoredReport() {
    return Optional.empty();
  }

  @Override
  protected Optional<UserReport> getUserReport() throws IOException {
    return Optional.empty();
  }
}
