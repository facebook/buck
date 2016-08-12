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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.LogConfigPaths;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class for gathering logs and other interesting information from buck.
 */
public abstract class AbstractReport {

  private final ProjectFilesystem filesystem;
  private final DefectReporter defectReporter;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final PrintStream output;
  private final ExtraInfoCollector extraInfoCollector;

  public AbstractReport(
      ProjectFilesystem filesystem,
      DefectReporter defectReporter,
      BuildEnvironmentDescription buildEnvironmentDescription,
      PrintStream output,
      ExtraInfoCollector extraInfoCollector) {
    this.filesystem = filesystem;
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

    ImmutableSet<BuildLogEntry> highlightedBuilds = promptForBuildSelection();
    Optional<UserReport> userReport = getUserReport();
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

    UserLocalConfiguration userLocalConfiguration =
        UserLocalConfiguration.of(isNoBuckCheckPresent(), getLocalConfigs());

    ImmutableSet<Path> includedPaths = FluentIterable.from(highlightedBuilds)
        .transformAndConcat(
            new Function<BuildLogEntry, Iterable<Path>>() {
              @Override
              public Iterable<Path> apply(BuildLogEntry input) {
                ImmutableSet.Builder<Path> result = ImmutableSet.builder();
                Optionals.addIfPresent(input.getRuleKeyLoggerLogFile(), result);
                result.add(input.getRelativePath());
                return result.build();
              }
            })
        .append(extraInfoPaths)
        .append(userLocalConfiguration.getLocalConfigs())
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
        .setUserLocalConfiguration(userLocalConfiguration)
        .build();

    output.println("Writing report, please wait..");
    return defectReporter.submitReport(defectReport);
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractUserReport {
    String getUserIssueDescription();
  }

  private ImmutableSet<Path> getLocalConfigs() {
    Path rootPath = filesystem.getRootPath();
    ImmutableSet<Path> knownUserLocalConfigs = ImmutableSet.of(
        Paths.get(BuckConfig.BUCK_CONFIG_OVERRIDE_FILE_NAME),
        LogConfigPaths.LOCAL_PATH,
        Paths.get(".watchman.local"),
        Paths.get(".buckjavaargs.local"),
        Paths.get(".bucklogging.local.properties"));
    Set<Path> foundUserlocalConfigs = new HashSet<>();

    for (Path localConfig : knownUserLocalConfigs) {
      if (Files.exists(rootPath.resolve(localConfig))) {
        foundUserlocalConfigs.add(localConfig);
      }
    }

    return ImmutableSet.copyOf(foundUserlocalConfigs);
  }

  private boolean isNoBuckCheckPresent() {
    return Files.exists(filesystem.getRootPath().resolve(".nobuckcheck"));
  }
}
