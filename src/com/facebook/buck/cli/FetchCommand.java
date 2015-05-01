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
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.step.TargetDevice;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.Proxy;

public class FetchCommand extends AbstractCommandRunner<BuildCommandOptions> {

  @Override
  BuildCommandOptions createOptions(BuckConfig buckConfig) {
    return new BuildCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(CommandRunnerParams params, BuildCommandOptions options)
      throws IOException, InterruptedException {

    ImmutableSet<BuildTarget> buildTargets =
        getBuildTargets(params, options.getArgumentsFormattedAsBuildTargets());

    if (buildTargets.isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least one build target to fetch.");
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(
          BuildEvent.started(buildTargets),
          params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(BuildEvent.started(buildTargets));
    }

    FetchTargetNodeToBuildRuleTransformer ruleGenerator = createFetchTransformer(options);
    TargetGraphToActionGraph transformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        ruleGenerator);

    ActionGraph actionGraph;
    try {
      TargetGraph targetGraph = params.getParser().buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(options.getBuckConfig()),
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          options.getEnableProfiling());

      actionGraph = transformer.apply(targetGraph);
      buildTargets = ruleGenerator.getDownloadableTargets();
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    int exitCode;
    try (CommandThreadManager pool =
        new CommandThreadManager("Fetch", options.getConcurrencyLimit());
         Build build = options.createBuild(
             options.getBuckConfig(),
             actionGraph,
             params.getRepository().getFilesystem(),
             params.getAndroidPlatformTargetSupplier(),
             new CachingBuildEngine(
                 pool.getExecutor(),
                 options.getBuckConfig().getSkipLocalBuildChainDepth().or(1L)),
             getArtifactCache(params, options),
             params.getConsole(),
             params.getBuckEventBus(),
             Optional.<TargetDevice>absent(),
             params.getPlatform(),
             params.getEnvironment(),
             params.getObjectMapper(),
             params.getClock())) {
      exitCode = build.executeAndPrintFailuresToConsole(
          buildTargets,
          options.isKeepGoing(),
          params.getConsole(),
          options.getPathToBuildReport());
    }

    params.getBuckEventBus().post(BuildEvent.finished(buildTargets, exitCode));

    return exitCode;
  }

  private FetchTargetNodeToBuildRuleTransformer createFetchTransformer(
      BuildCommandOptions options) {
    Optional<String> defaultMavenRepo = options.getBuckConfig().getValue("download", "maven_repo");
    Downloader downloader = new HttpDownloader(Optional.<Proxy>absent(), defaultMavenRepo);
    Description<?> description = new RemoteFileDescription(downloader);
    return new FetchTargetNodeToBuildRuleTransformer(
        ImmutableSet.<Description<?>>of(description)
    );
  }

  @Override
  String getUsageIntro() {
    return "fetch remote resources";
  }
}
