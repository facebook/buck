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
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
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

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;
  @Nullable private Build build;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  public BuildCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  BuildCommandOptions createOptions(BuckConfig buckConfig) {
    return new BuildCommandOptions(buckConfig);
  }

  @Override
  @SuppressWarnings("PMD.PrematureDeclaration")
  int runCommandWithOptionsInternal(BuildCommandOptions options)
      throws IOException, InterruptedException {
    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache();


    buildTargets = getBuildTargets(options.getArgumentsFormattedAsBuildTargets());

    if (buildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = options.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        console.getStdErr().println(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10))));
      }
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (getParser().getParseStartTime().isPresent()) {
      getBuckEventBus().post(
          BuildEvent.started(buildTargets),
          getParser().getParseStartTime().get());
    } else {
      getBuckEventBus().post(BuildEvent.started(buildTargets));
    }

    // Parse the build files to create a ActionGraph.
    ActionGraph actionGraph;
    try {
      TargetGraph targetGraph = getParser().buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(options.getBuckConfig()),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
      actionGraph = targetGraphTransformer.apply(targetGraph);
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Create and execute the build.
    build = options.createBuild(
        options.getBuckConfig(),
        actionGraph,
        getProjectFilesystem(),
        getAndroidDirectoryResolver(),
        getBuildEngine(),
        artifactCache,
        console,
        getBuckEventBus(),
        Optional.<TargetDevice>absent(),
        getCommandRunnerParams().getPlatform(),
        getCommandRunnerParams().getEnvironment(),
        getCommandRunnerParams().getObjectMapper(),
        getCommandRunnerParams().getClock());
    int exitCode = 0;
    try {
      exitCode = build.executeAndPrintFailuresToConsole(
          buildTargets,
          options.isKeepGoing(),
          console,
          options.getPathToBuildReport());
    } finally {
      build.close(); // Can't use try-with-resources as build is returned by getBuild.
    }
    getBuckEventBus().post(BuildEvent.finished(buildTargets, exitCode));

    return exitCode;
  }

  Build getBuild() {
    Preconditions.checkNotNull(build);
    return build;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  String getUsageIntro() {
    return "Specify one build rule to build.";
  }
}
