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

import static com.facebook.buck.rage.AbstractRageConfig.RageProtocolVersion;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.LogConfigPaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Base class for gathering logs and other interesting information from buck.
 */
public abstract class AbstractReport {

  private static final Logger LOG = Logger.get(AbstractReport.class);

  private final ProjectFilesystem filesystem;
  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final PrintStream output;
  private final RageConfig rageConfig;
  private final ExtraInfoCollector extraInfoCollector;

  public AbstractReport(
      ProjectFilesystem filesystem,
      DefectReporter defectReporter,
      BuildEnvironmentDescription buildEnvironmentDescription,
      PrintStream output,
      RageConfig rageBuckConfig,
      ExtraInfoCollector extraInfoCollector) {
    this.filesystem = filesystem;
    this.defectReporter = defectReporter;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.output = output;
    this.rageConfig = rageBuckConfig;
    this.extraInfoCollector = extraInfoCollector;
  }

  protected abstract ImmutableSet<BuildLogEntry> promptForBuildSelection() throws IOException;

  protected abstract Optional<SourceControlInfo> getSourceControlInfo()
      throws IOException, InterruptedException;

  protected abstract Optional<UserReport> getUserReport() throws IOException;

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
    } catch (DefaultExtraInfoCollector.ExtraInfoExecutionException e) {
      output.printf("There was a problem gathering additional information: %s. " +
          "The results will not be attached to the report.", e.getMessage());
    }

    UserLocalConfiguration userLocalConfiguration =
        UserLocalConfiguration.of(isNoBuckCheckPresent(), getLocalConfigs());

    ImmutableSet<Path> includedPaths = FluentIterable.from(selectedBuilds)
        .transformAndConcat(
            new Function<BuildLogEntry, Iterable<Path>>() {
              @Override
              public Iterable<Path> apply(BuildLogEntry input) {
                ImmutableSet.Builder<Path> result = ImmutableSet.builder();
                Optionals.addIfPresent(input.getRuleKeyLoggerLogFile(), result);
                Optionals.addIfPresent(input.getMachineReadableLogFile(), result);
                result.add(input.getRelativePath());
                return result.build();
              }
            })
        .append(extraInfoPaths)
        .append(userLocalConfiguration.getLocalConfigsContents().keySet())
        .append(getTracePathsOfBuilds(selectedBuilds))
        .toSet();

    DefectReport defectReport = DefectReport.builder()
        .setUserReport(userReport)
        .setHighlightedBuildIds(
            FluentIterable.from(selectedBuilds)
                .transformAndConcat(
                    new Function<BuildLogEntry, Iterable<BuildId>>() {
                      @Override
                      public Iterable<BuildId> apply(BuildLogEntry input) {
                        return OptionalCompat.asSet(input.getBuildId());
                      }
                    }))
        .setBuildEnvironmentDescription(buildEnvironmentDescription)
        .setSourceControlInfo(sourceControlInfo)
        .setIncludedPaths(includedPaths)
        .setExtraInfo(extraInfo)
        .setUserLocalConfiguration(userLocalConfiguration)
        .build();

    output.println("Writing report, please wait..");
    return Optional.of(defectReporter.submitReport(defectReport));
  }

  public void presentDefectSubmitResult(Optional<DefectSubmitResult> defectSubmitResult) {
    if (!defectSubmitResult.isPresent()) {
      output.println("No logs of interesting commands were found. Check if buck-out/log contains " +
          "commands except buck launch & buck rage.");
      return;
    }
    DefectSubmitResult result = defectSubmitResult.get();
    // If request has an empty isRequestSuccessful, it means we did not try to upload it somewhere.
    if (!result.getIsRequestSuccessful().isPresent()) {
      if (result.getReportSubmitLocation().isPresent()) {
        output.printf("Report saved at %s\n", result.getReportSubmitLocation().get());
      } else {
        output.printf(
            "=> Failed to save report locally. Reason: %s\n",
            result.getReportSubmitErrorMessage().orElse("Unknown"));
      }
      return;
    }

    if (result.getIsRequestSuccessful().get()) {
     if (result.getRequestProtocol().equals(RageProtocolVersion.SIMPLE)) {
       output.printf("%s", result.getReportSubmitMessage().get());
     } else {
       String message = "=> Upload was successful.\n";
       if (result.getReportSubmitLocation().isPresent()) {
         message += "=> Report was uploaded to " + result.getReportSubmitLocation().get() + "\n";
       }
       if (result.getReportSubmitMessage().isPresent()) {
         message += "=> Full Response was: " + result.getReportSubmitMessage().get() + "\n";
       }
       output.print(message);
     }
    } else {
      output.printf(
          "=> Failed to upload report because of error: %s.\n=> Report was saved locally at %s\n",
          result.getReportSubmitErrorMessage().get(),
          result.getReportSubmitLocation());
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractUserReport {
    String getUserIssueDescription();
  }

  private ImmutableMap<Path, String> getLocalConfigs() {
    Path rootPath = filesystem.getRootPath();
    ImmutableSet<Path> knownUserLocalConfigs = ImmutableSet.of(
        Paths.get(BuckConfig.BUCK_CONFIG_OVERRIDE_FILE_NAME),
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
   * It returns a list of trace files that corresponds to builds while respecting the maximum
   * size of the final zip file.
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
          if (rageConfig.getReportMaxSizeBytes().isPresent()) {
            if (reportSizeBytes + traceFileSizeBytes < rageConfig.getReportMaxSizeBytes().get()) {
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
}
