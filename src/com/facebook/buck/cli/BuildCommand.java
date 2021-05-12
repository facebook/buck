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
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetOutputLabelParser;
import com.facebook.buck.core.rules.BuildRule;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/** {@code buck build} command handler. */
public class BuildCommand extends AbstractBuildCommand {

  public BuildCommand() {
    this(ImmutableList.of());
  }

  public BuildCommand(List<String> arguments) {
    this.arguments.addAll(arguments);
  }

  @Override
  GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, IOException, InterruptedException {
    ImmutableList<TargetNodeSpec> specs;
    try {
      specs =
          targetNodeSpecEnhancer.apply(
              parseArgumentsAsTargetNodeSpecs(
                  params.getCells(),
                  params.getClientWorkingDir(),
                  getArguments(),
                  params.getBuckConfig()));
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
    TargetGraphCreationResult unversionedTargetGraph =
        createUnversionedTargetGraph(params, executorService, specs);

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
        justBuildTarget == null
            ? matchBuildTargetsWithLabelsFromSpecs(
                specs, targetGraphForLocalBuild.getBuildTargets())
            : getBuildTargetsWithOutputsForJustBuild(params, actionGraph, justBuildTarget);

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.of(unversionedTargetGraph, versionedTargetGraph, actionGraph);

    return ImmutableGraphsAndBuildTargets.ofImpl(actionAndTargetGraphs, buildTargetsWithOutputs);
  }

  private TargetGraphCreationResult createUnversionedTargetGraph(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      ImmutableList<TargetNodeSpec> specs)
      throws IOException, InterruptedException, ActionGraphCreationException {
    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params
          .getParser()
          .buildTargetGraphWithoutTopLevelConfigurationTargets(
              createParsingContext(params.getCells(), executor)
                  .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                  .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode()),
              specs,
              params.getTargetConfiguration());
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private ImmutableSet<BuildTargetWithOutputs> getBuildTargetsWithOutputsForJustBuild(
      CommandRunnerParams params,
      ActionGraphAndBuilder actionGraphAndBuilder,
      String justBuildTarget)
      throws ActionGraphCreationException {
    BuildTargetOutputLabelParser.TargetWithOutputLabel targetWithOutputLabel =
        BuildTargetOutputLabelParser.getBuildTargetNameWithOutputLabel(justBuildTarget);
    UnconfiguredBuildTarget explicitTarget =
        params
            .getUnconfiguredBuildTargetFactory()
            .create(
                targetWithOutputLabel.getTargetName(),
                params.getCells().getRootCell().getCellNameResolver());

    ImmutableSet<BuildTargetWithOutputs> targets =
        StreamSupport.stream(actionGraphAndBuilder.getActionGraph().getNodes().spliterator(), false)
            .map(BuildRule::getBuildTarget)
            .filter(t -> t.getUnconfiguredBuildTarget().equals(explicitTarget))
            .map(t -> BuildTargetWithOutputs.of(t, targetWithOutputLabel.getOutputLabel()))
            .collect(ImmutableSet.toImmutableSet());

    if (targets.isEmpty()) {
      throw new ActionGraphCreationException(
          "Targets specified via `--just-build` must be a subset of action graph.");
    }

    return targets;
  }
}
