/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.command.Build;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class BuildCommandOptions extends AbstractCommandOptions {

  @Option(name = "--num-threads", aliases = "-j", usage = "Default is 1.25 * num processors.")
  private int numThreads = (int) (Runtime.getRuntime().availableProcessors() * 1.25);

  @Option(
      name = "--keep-going",
      usage = "Keep going when some targets can't be made.")
  private boolean keepGoing = false;

  @Option(
      name = "--build-report",
      usage = "File where build report will be written.")
  @Nullable
  private Path buildReport = null;

  @Option(name = "--build-dependencies",
      aliases = "-b",
      usage = "How to handle including dependencies")
  @Nullable
  private BuildDependencies buildDependencies = null;


  @Argument
  private List<String> arguments = Lists.newArrayList();

  public BuildCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);

    setNumThreadsFromConfig(buckConfig);
  }

  private Supplier<BuildDependencies> buildDependenciesSupplier =
      Suppliers.memoize(new Supplier<BuildDependencies>() {
        @Override
        public BuildDependencies get() {
          if (buildDependencies != null) {
            return buildDependencies;
          } else if (getBuckConfig().getBuildDependencies().isPresent()) {
            return getBuckConfig().getBuildDependencies().get();
          } else {
            return BuildDependencies.getDefault();
          }
        }
      });

  private void setNumThreadsFromConfig(BuckConfig buckConfig) {
    ImmutableMap<String, String> build = buckConfig.getEntriesForSection("build");
    if (build.containsKey("threads")) {
      try {
        numThreads = Integer.parseInt(build.get("threads"));
      } catch (NumberFormatException e) {
        throw new HumanReadableException(
            "Unable to determine number of threads to use from building from buck config file. " +
                "Value used was '%s'", build.get("threads"));
      }
    }
  }

  public List<String> getArguments() {
    return arguments;
  }

  public void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public ImmutableSet<String> getArgumentsFormattedAsBuildTargets() {
    return ImmutableSet.copyOf(getCommandLineBuildTargetNormalizer().normalizeAll(getArguments()));
  }

  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }


  int getNumThreads() {
    return numThreads;
  }

  public boolean isKeepGoing() {
    return keepGoing;
  }

  /**
   * @return an absolute path or {@link Optional#absent()}.
   */
  public Optional<Path> getPathToBuildReport() {
    return Optional.fromNullable(getBuckConfig().resolvePathThatMayBeOutsideTheProjectFilesystem(
        buildReport));
  }

  public BuildDependencies getBuildDependencies() {
    return buildDependenciesSupplier.get();
  }

  Build createBuild(BuckConfig buckConfig,
      ActionGraph graph,
      ProjectFilesystem projectFilesystem,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      Console console,
      BuckEventBus eventBus,
      Optional<TargetDevice> targetDevice,
      Platform platform,
      ImmutableMap<String, String> environment,
      ObjectMapper objectMapper,
      Clock clock,
      ListeningExecutorService service) {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("Creating a build with %d threads.\n", numThreads);
    }
    return new Build(
        graph,
        targetDevice,
        projectFilesystem,
        androidPlatformTargetSupplier,
        buildEngine,
        artifactCache,
        service,
        getBuckConfig().createDefaultJavaPackageFinder(),
        console,
        buckConfig.getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled(),
        getBuildDependencies(),
        eventBus,
        platform,
        environment,
        objectMapper,
        clock);
  }

}
