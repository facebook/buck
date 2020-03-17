/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;

/** A build-like command-like object to be used when build targets are given with configurations. */
// TODO(nga): IDE generators should not use build command, but instead build directly.
public class BuildCommandForProjectGenerators extends AbstractBuildCommand {

  private final ImmutableList<BuildTarget> buildTargets;

  public BuildCommandForProjectGenerators(ImmutableList<BuildTarget> buildTargets) {
    this.buildTargets = buildTargets;
    this.arguments.addAll(
        buildTargets.stream().map(BuildTarget::toString).collect(ImmutableList.toImmutableList()));
  }

  @Override
  GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, IOException, InterruptedException {

    TargetGraphCreationResult unversionedTargetGraph =
        createUnversionedTargetGraph(params, executorService);

    Optional<TargetGraphCreationResult> versionedTargetGraph = Optional.empty();
    try {
      if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
        versionedTargetGraph = Optional.of(toVersionedTargetGraph(params, unversionedTargetGraph));
      }
    } catch (VersionException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    TargetGraphCreationResult targetGraphForLocalBuild =
        ActionAndTargetGraphs.getTargetGraph(unversionedTargetGraph, versionedTargetGraph);
    checkSingleBuildTargetSpecifiedForOutBuildMode(targetGraphForLocalBuild);
    ActionGraphAndBuilder actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild, ruleKeyLogger);

    ImmutableSet<BuildTargetWithOutputs> buildTargetsWithOutputs =
        targetGraphForLocalBuild.getBuildTargets().stream()
            .map(t -> BuildTargetWithOutputs.of(t, OutputLabel.defaultLabel()))
            .collect(ImmutableSet.toImmutableSet());

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.of(unversionedTargetGraph, versionedTargetGraph, actionGraph);

    return ImmutableGraphsAndBuildTargets.ofImpl(actionAndTargetGraphs, buildTargetsWithOutputs);
  }

  private TargetGraphCreationResult createUnversionedTargetGraph(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, ActionGraphCreationException {
    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params
          .getParser()
          .buildTargetGraph(
              createParsingContext(params.getCells(), executor)
                  .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                  .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode()),
              ImmutableSet.copyOf(buildTargets));
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }
}
