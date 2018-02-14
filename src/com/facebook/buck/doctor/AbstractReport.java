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
import com.facebook.buck.doctor.config.SourceControlInfo;
import com.facebook.buck.doctor.config.UserLocalConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.LogConfigPaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

/** Base class for gathering logs and other interesting information from buck. */
public abstract class AbstractReport {

  private static final Logger LOG = Logger.get(AbstractReport.class);

  private final ProjectFilesystem filesystem;
  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final VersionControlStatsGenerator versionControlStatsGenerator;
  private final Console output;
  private final DoctorConfig doctorConfig;
  private final ExtraInfoCollector extraInfoCollector;
  private final Optional<WatchmanDiagReportCollector> watchmanDiagReportCollector;

  public AbstractReport(
      ProjectFilesystem filesystem,
      DefectReporter defectReporter,
      BuildEnvironmentDescription buildEnvironmentDescription,
      VersionControlStatsGenerator versionControlStatsGenerator,
      Console output,
      DoctorConfig doctorBuckConfig,
      ExtraInfoCollector extraInfoCollector,
      Optional<WatchmanDiagReportCollector> watchmanDiagReportCollector) {
    this.filesystem = filesystem;
    this.defectReporter = defectReporter;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.versionControlStatsGenerator = versionControlStatsGenerator;
    this.output = output;
    this.doctorConfig = doctorBuckConfig;
    this.extraInfoCollector = extraInfoCollector;
    this.watchmanDiagReportCollector = watchmanDiagReportCollector;
  }

  protected abstract ImmutableSet<BuildLogEntry> promptForBuildSelection();

  protected Optional<SourceControlInfo> getSourceControlInfo() throws InterruptedException {
    Optional<FullVersionControlStats> versionControlStatsOptional =
        versionControlStatsGenerator.generateStats(VersionControlStatsGenerator.Mode.FULL);
    if (!versionControlStatsOptional.isPresent()) {
      return Optional.empty();
    }
    FullVersionControlStats versionControlStats = versionControlStatsOptional.get();
    return Optional.of(
        SourceControlInfo.of(
            versionControlStats.getCurrentRevisionId(),
            versionControlStats.getBaseBookmarks(),
            Optional.of(versionControlStats.getBranchedFromMasterRevisionId()),
            Optional.of(versionControlStats.getBranchedFromMasterTS()),
            versionControlStats.getDiff(),
            versionControlStats.getPathsChangedInWorkingDirectory()));
  }

  protected abstract Optional<UserReport> getUserReport();

  protected abstract Optional<FileChangesIgnoredReport> getFileChangesIgnoredReport()
      throws IOException, InterruptedException;

  public final Optional<DefectSubmitResult> collectAndSubmitResult()
      throws IOException, InterruptedException {

    ImmutableSet<BuildLogEntry> selectedBuilds = promptForBuildSelection();
    if (selectedBuilds.isEmpty()) {
      return Optional.empty();
    }

    Optional<UserReport> userReport = getUserReport();
    Optional<SourceControlInfo> sourceControlInfo = getSourceControlInfo();

    ImmutableSet<Path> extraInfoPaths = ImmutableSet.of();
    Optional<String> extraInfo = Optional.empty();
    try {
      Optional<ExtraInfoResult> extraInfoResultOptional = extraInfoCollector.run();
      if (extraInfoResultOptional.isPresent()) {
        extraInfoPaths = extraInfoResultOptional.get().getExtraFiles();
        extraInfo = Optional.of(extraInfoResultOptional.get().getOutput());
      }
    } catch (ExtraInfoCollector.ExtraInfoExecutionException e) {
      output.printErrorText(
          "There was a problem gathering additional information: %s. "
              + "The results will not be attached to the report.",
          e.getMessage());
    }

    Optional<FileChangesIgnoredReport> fileChangesIgnoredReport = getFileChangesIgnoredReport();

    UserLocalConfiguration userLocalConfiguration =
        UserLocalConfiguration.of(isNoBuckCheckPresent(), getLocalConfigs());

    ImmutableSet<Path> includedPaths =
        FluentIterable.from(selectedBuilds)
            .transformAndConcat(
                input -> {
                  Builder<Path> result = ImmutableSet.builder();
                  Optionals.addIfPresent(input.getRuleKeyLoggerLogFile(), result);
                  Optionals.addIfPresent(input.getMachineReadableLogFile(), result);
                  Optionals.addIfPresent(input.getRuleKeyDiagKeysFile(), result);
                  Optionals.addIfPresent(input.getRuleKeyDiagGraphFile(), result);
                  result.add(input.getRelativePath());
                  return result.build();
                })
            .append(extraInfoPaths)
            .append(userLocalConfiguration.getLocalConfigsContents().keySet())
            .append(getTracePathsOfBuilds(selectedBuilds))
            .append(
                fileChangesIgnoredReport
                    .flatMap(r -> r.getWatchmanDiagReport())
                    .map(ImmutableList::of)
                    .orElse(ImmutableList.of()))
            .toSet();

    DefectReport defectReport =
        DefectReport.builder()
            .setUserReport(userReport)
            .setHighlightedBuildIds(
                RichStream.from(selectedBuilds)
                    .map(BuildLogEntry::getBuildId)
                    .flatMap(Optionals::toStream)
                    .toOnceIterable())
            .setBuildEnvironmentDescription(buildEnvironmentDescription)
            .setSourceControlInfo(sourceControlInfo)
            .setIncludedPaths(includedPaths)
            .setExtraInfo(extraInfo)
            .setFileChangesIgnoredReport(fileChangesIgnoredReport)
            .setUserLocalConfiguration(userLocalConfiguration)
            .build();

    output.getStdOut().println("Writing report, please wait..\n");
    return Optional.of(defectReporter.submitReport(defectReport));
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractUserReport {

    @Value.Parameter
    String getUserIssueDescription();

    @Value.Parameter
    String getIssueCategory();
  }

  private ImmutableMap<Path, String> getLocalConfigs() {
    Path rootPath = filesystem.getRootPath();
    ImmutableSet<Path> knownUserLocalConfigs =
        ImmutableSet.of(
            Paths.get(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME),
            LogConfigPaths.LOCAL_PATH,
            Paths.get(".watchman.local"),
            Paths.get(".buckjavaargs.local"),
            Paths.get(".bucklogging.local.properties"));

    ImmutableMap.Builder<Path, String> localConfigs = ImmutableMap.builder();
    for (Path localConfig : knownUserLocalConfigs) {
      try {
        localConfigs.put(
            localConfig,
            new String(Files.readAllBytes(rootPath.resolve(localConfig)), StandardCharsets.UTF_8));
      } catch (FileNotFoundException e) {
        LOG.debug("%s was not found.", localConfig);
      } catch (IOException e) {
        LOG.warn("Failed to read contents of %s.", rootPath.resolve(localConfig).toString());
      }
    }

    return localConfigs.build();
  }

  /**
   * It returns a list of trace files that corresponds to builds while respecting the maximum size
   * of the final zip file.
   *
   * @param entries the highlighted builds
   * @return a set of paths that points to the corresponding traces.
   */
  private ImmutableSet<Path> getTracePathsOfBuilds(ImmutableSet<BuildLogEntry> entries) {
    ImmutableSet.Builder<Path> tracePaths = new ImmutableSet.Builder<>();
    long reportSizeBytes = 0;
    for (BuildLogEntry entry : entries) {
      reportSizeBytes += entry.getSize();
      if (entry.getTraceFile().isPresent()) {
        try {
          Path traceFile = filesystem.getPathForRelativeExistingPath(entry.getTraceFile().get());
          long traceFileSizeBytes = Files.size(traceFile);
          if (doctorConfig.getReportMaxSizeBytes().isPresent()) {
            if (reportSizeBytes + traceFileSizeBytes < doctorConfig.getReportMaxSizeBytes().get()) {
              tracePaths.add(entry.getTraceFile().get());
              reportSizeBytes += traceFileSizeBytes;
            }
          } else {
            tracePaths.add(entry.getTraceFile().get());
            reportSizeBytes += traceFileSizeBytes;
          }
        } catch (IOException e) {
          LOG.info("Trace path %s wasn't valid, skipping it.", entry.getTraceFile().get());
        }
      }
    }
    return tracePaths.build();
  }

  private boolean isNoBuckCheckPresent() {
    return Files.exists(filesystem.getRootPath().resolve(".nobuckcheck"));
  }

  @VisibleForTesting
  Optional<FileChangesIgnoredReport> runWatchmanDiagReportCollector(UserInput input)
      throws IOException, InterruptedException {
    if (!watchmanDiagReportCollector.isPresent()
        || !input.confirm(
            "Is buck not picking up changes to files? "
                + "(saying 'yes' will run extra consistency checks)")) {
      return Optional.empty();
    }

    Optional<Path> watchmanDiagReport = Optional.empty();
    try {
      watchmanDiagReport = Optional.of(watchmanDiagReportCollector.get().run());
    } catch (ExtraInfoCollector.ExtraInfoExecutionException e) {
      output.printErrorText(
          "There was a problem getting the watchman-diag report: %s. "
              + "The information will be omitted from the report.",
          e);
    }

    return Optional.of(
        FileChangesIgnoredReport.builder().setWatchmanDiagReport(watchmanDiagReport).build());
  }
}
