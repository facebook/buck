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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class BuildCommand extends AbstractCommand {

  @Option(name = "--num-threads", aliases = "-j", usage = "Default is 1.25 * num processors.")
  @Nullable
  private Integer numThreads = null;

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

  @Nullable
  @Option(name = "--load-limit",
      aliases = "-L",
      usage = "[Float] Do not start new jobs when system load is above this level." +
      " See uptime(1).")
  private Double loadLimit = null;

  @Nullable
  @Option(
      name = "--just-build",
      usage = "For debugging, limits the build to a specific target in the action graph.",
      hidden = true)
  private String justBuildTarget = null;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }


  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }


  int getNumThreads(BuckConfig buckConfig) {
    if (numThreads == null) {
      ImmutableMap<String, String> build = buckConfig.getEntriesForSection("build");
      if (build.containsKey("threads")) {
        try {
          numThreads = Integer.parseInt(build.get("threads"));
        } catch (NumberFormatException e) {
          throw new HumanReadableException(
              e,
              "Unable to determine number of threads to use from building from buck config file. " +
                  "Value used was '%s'", build.get("threads"));
        }
      } else {
        numThreads = (int) (Runtime.getRuntime().availableProcessors() * 1.25);
      }
    }
    return numThreads;
  }

  public boolean isKeepGoing() {
    return keepGoing;
  }

  public double getLoadLimit(BuckConfig buckConfig) {
    if (loadLimit == null) {
      ImmutableMap<String, String> build = buckConfig.getEntriesForSection("build");
      if (build.containsKey("load_limit")) {
        try {
          loadLimit = Double.parseDouble(build.get("load_limit"));
        } catch (NumberFormatException e) {
          throw new HumanReadableException(
              e,
              "Unable to determine load limit to use from building from buck config file. " +
                  "Value used was '%s'", build.get("load_limit"));
        }
      } else {
        loadLimit = Double.POSITIVE_INFINITY;
      }
    }
    return loadLimit;
  }

  public ConcurrencyLimit getConcurrencyLimit(BuckConfig buckConfig) {
    return new ConcurrencyLimit(getNumThreads(buckConfig), getLoadLimit(buckConfig));
  }

  /**
   * @return an absolute path or {@link Optional#absent()}.
   */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.fromNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  public BuildDependencies getBuildDependencies(BuckConfig buckConfig) {
    if (buildDependencies != null) {
      return buildDependencies;
    } else if (buckConfig.getBuildDependencies().isPresent()) {
      return buckConfig.getBuildDependencies().get();
    } else {
      return BuildDependencies.getDefault();
    }
  }

  Build createBuild(
      BuckConfig buckConfig,
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
      Clock clock) {
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
        buckConfig.createDefaultJavaPackageFinder(),
        console,
        buckConfig.getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled(),
        getBuildDependencies(buckConfig),
        eventBus,
        platform,
        environment,
        objectMapper,
        clock,
        getConcurrencyLimit(buckConfig));
  }

  @Nullable private Build lastBuild;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  @Override
  @SuppressWarnings("PMD.PrematureDeclaration")
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache(params);


    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = params.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        params.getConsole().getStdErr().println(String.format(
                "Try building one of the following targets:\n%s",
                Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10))));
      }
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          BuildEvent.started(getArguments()),
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(BuildEvent.started(getArguments()));
    }

    // Parse the build files to create a ActionGraph.
    ActionGraph actionGraph;
    try {
      Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  params.getRepository().getFilesystem().getIgnorePaths(),
                  getArguments()),
              new ParserConfig(params.getBuckConfig()),
              params.getBuckEventBus(),
              params.getConsole(),
              params.getEnvironment(),
              getEnableProfiling());
      buildTargets = result.getFirst();
      actionGraph = new TargetGraphToActionGraph(
          params.getBuckEventBus(),
          new BuildTargetNodeToBuildRuleTransformer()).apply(result.getSecond());
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // If the user specified an explicit build target, use that.
    if (justBuildTarget != null) {
      BuildTargetParser parser = new BuildTargetParser();
      BuildTarget explicitTarget =
          parser.parse(justBuildTarget, BuildTargetPatternParser.fullyQualified(parser));
      ImmutableSet<BuildRule> actionGraphRules = Preconditions.checkNotNull(actionGraph.getNodes());
      ImmutableSet<BuildTarget> actionGraphTargets =
          ImmutableSet.copyOf(Iterables.transform(actionGraphRules, HasBuildTarget.TO_TARGET));
      if (!actionGraphTargets.contains(explicitTarget)) {
        params.getConsole().printBuildFailure(
            "Targets specified via `--just-build` must be a subset of action graph.");
        return 1;
      }
      buildTargets = ImmutableSet.of(explicitTarget);
    }

    try (CommandThreadManager pool = new CommandThreadManager(
        "Build",
        getConcurrencyLimit(params.getBuckConfig()));
         Build build = createBuild(
             params.getBuckConfig(),
             actionGraph,
             params.getRepository().getFilesystem(),
             params.getAndroidPlatformTargetSupplier(),
             new CachingBuildEngine(
                 pool.getExecutor(),
                 params.getBuckConfig().getSkipLocalBuildChainDepth().or(1L)),
             artifactCache,
             params.getConsole(),
             params.getBuckEventBus(),
             Optional.<TargetDevice>absent(),
             params.getPlatform(),
             params.getEnvironment(),
             params.getObjectMapper(),
             params.getClock())) {
      lastBuild = build;
      int exitCode = build.executeAndPrintFailuresToConsole(
          buildTargets,
          isKeepGoing(),
          params.getConsole(),
          getPathToBuildReport(params.getBuckConfig()));
      params.getBuckEventBus().post(BuildEvent.finished(getArguments(), exitCode));
      return exitCode;
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  Build getBuild() {
    Preconditions.checkNotNull(lastBuild);
    return lastBuild;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

}
