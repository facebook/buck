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

import com.facebook.buck.command.Build;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.step.TargetDevice;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import java.io.IOException;

import javax.annotation.Nullable;

public class BuildCommand extends AbstractCommandRunner<BuildCommandOptions> {

  @Nullable private Build lastBuild;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  @Override
  BuildCommandOptions createOptions() {
    return new BuildCommandOptions();
  }

  @Override
  @SuppressWarnings("PMD.PrematureDeclaration")
  int runCommandWithOptionsInternal(CommandRunnerParams params, BuildCommandOptions options)
      throws IOException, InterruptedException {
    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache(params, options);


    if (options.getArguments().isEmpty()) {
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
          BuildEvent.started(options.getArguments()),
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(BuildEvent.started(options.getArguments()));
    }

    // Parse the build files to create a ActionGraph.
    ActionGraph actionGraph;
    try {
      Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              options.parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  params.getRepository().getFilesystem().getIgnorePaths(),
                  options.getArguments()),
              new ParserConfig(params.getBuckConfig()),
              params.getBuckEventBus(),
              params.getConsole(),
              params.getEnvironment(),
              options.getEnableProfiling());
      buildTargets = result.getFirst();
      actionGraph = new TargetGraphToActionGraph(
          params.getBuckEventBus(),
          new BuildTargetNodeToBuildRuleTransformer()).apply(result.getSecond());
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    try (CommandThreadManager pool = new CommandThreadManager(
            "Build",
            options.getConcurrencyLimit(params.getBuckConfig()));
         Build build = options.createBuild(
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
          options.isKeepGoing(),
          params.getConsole(),
          options.getPathToBuildReport(params.getBuckConfig()));
      params.getBuckEventBus().post(BuildEvent.finished(options.getArguments(), exitCode));
      return exitCode;
    }
  }

  Build getBuild() {
    Preconditions.checkNotNull(lastBuild);
    return lastBuild;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  String getUsageIntro() {
    return "Specify one build rule to build.";
  }
}
