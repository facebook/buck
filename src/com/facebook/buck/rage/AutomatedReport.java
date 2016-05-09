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
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Responsible for gathering logs and other interesting information from buck without user
 * interaction.
 */
public class AutomatedReport {
  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final BuildLogHelper buildLogHelper;
  private final Optional<VcsInfoCollector> vcsInfoHelper;
  private final PrintStream output;

  public AutomatedReport(
      DefectReporter defectReporter,
      ProjectFilesystem filesystem,
      PrintStream output,
      BuildEnvironmentDescription buildEnvironmentDescription,
      Optional<VcsInfoCollector> vcsInfoHelper) {
    this.defectReporter = defectReporter;
    this.output = output;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.vcsInfoHelper = vcsInfoHelper;
    this.buildLogHelper = new BuildLogHelper(filesystem);
  }

  public DefectSubmitResult collectAndSubmitResult()
      throws IOException, InterruptedException {
    Optional<SourceControlInfo> sourceControlInfo = Optional.absent();
    if (vcsInfoHelper.isPresent()) {
      try {
        sourceControlInfo = Optional.of(vcsInfoHelper.get().gatherScmInformation());
      } catch (VersionControlCommandFailedException e) {
        output.printf("Failed to get source control information: %s, proceeding regardless.\n", e);
      }
    }

    ImmutableList<BuildLogEntry> buildLogs = buildLogHelper.getBuildLogs();
    DefectReport defectReport = DefectReport.builder()
        .setBuildEnvironmentDescription(buildEnvironmentDescription)
        .setSourceControlInfo(sourceControlInfo)
        .setIncludedPaths(
            FluentIterable.from(buildLogs)
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
}
