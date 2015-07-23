/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.HttpDownloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.keys.InputBasedRuleKeyBuilderFactory;
import com.facebook.buck.step.TargetDevice;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.Proxy;

public class FetchCommand extends BuildCommand {

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target to fetch.");
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    BuildEvent.Started started = BuildEvent.started(getArguments());
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          started,
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(started);
    }

    FetchTargetNodeToBuildRuleTransformer ruleGenerator = createFetchTransformer(params);
    TargetGraphToActionGraph transformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        ruleGenerator,
        params.getFileHashCache());

    ActionGraph actionGraph;
    ImmutableSet<BuildTarget> buildTargets;
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
      actionGraph = transformer.apply(result.getSecond());
      buildTargets = ruleGenerator.getDownloadableTargets();
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    int exitCode;
    try (CommandThreadManager pool =
             new CommandThreadManager("Fetch", getConcurrencyLimit(params.getBuckConfig()));
         Build build = createBuild(
             params.getBuckConfig(),
             actionGraph,
             params.getRepository().getFilesystem(),
             params.getAndroidPlatformTargetSupplier(),
             new CachingBuildEngine(
                 pool.getExecutor(),
                 getBuildEngineMode().or(params.getBuckConfig().getBuildEngineMode()),
                 new InputBasedRuleKeyBuilderFactory(
                     params.getFileHashCache(),
                     new SourcePathResolver(transformer.getRuleResolver()))),
             getArtifactCache(params),
             params.getConsole(),
             params.getBuckEventBus(),
             Optional.<TargetDevice>absent(),
             params.getPlatform(),
             params.getEnvironment(),
             params.getObjectMapper(),
             params.getClock())) {
      exitCode = build.executeAndPrintFailuresToConsole(
          buildTargets,
          isKeepGoing(),
          params.getConsole(),
          getPathToBuildReport(params.getBuckConfig()));
    }

    params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));

    return exitCode;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private FetchTargetNodeToBuildRuleTransformer createFetchTransformer(CommandRunnerParams params) {
    Optional<String> defaultMavenRepo = params.getBuckConfig().getValue("download", "maven_repo");
    Downloader downloader = new HttpDownloader(Optional.<Proxy>absent(), defaultMavenRepo);
    Description<?> description = new RemoteFileDescription(downloader);
    return new FetchTargetNodeToBuildRuleTransformer(
        ImmutableSet.<Description<?>>of(description)
    );
  }

  @Override
  public String getShortDescription() {
    return "downloads remote resources to your local machine";
  }

}
