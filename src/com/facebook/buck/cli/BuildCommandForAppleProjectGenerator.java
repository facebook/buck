/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;
import java.util.function.Function;

/**
 * A build-like command-like object used to trigger building the specified targets, using the given
 * target graph and action graph.
 *
 * <p>This is used in Apple project generation. We need to build targets such as header maps as part
 * of project generation to ensure indexing success when the user first opens the generated Xcode
 * workspace.
 */
// TODO(nga): IDE generators should not use build command, but instead build directly.
public class BuildCommandForAppleProjectGenerator extends AbstractBuildCommand {

  private final ImmutableList<BuildTarget> buildTargets;
  private final ActionGraphBuilder actionGraphBuilder;
  private final TargetGraphCreationResult targetGraph;

  public BuildCommandForAppleProjectGenerator(
      ImmutableList<BuildTarget> buildTargets,
      TargetGraphCreationResult targetGraph,
      ActionGraphBuilder actionGraphBuilder) {
    this.buildTargets = buildTargets;
    this.actionGraphBuilder = actionGraphBuilder;
    this.targetGraph = targetGraph;
    this.arguments.addAll(
        buildTargets.stream().map(BuildTarget::toString).collect(ImmutableList.toImmutableList()));
  }

  @Override
  GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, InterruptedException {

    Optional<TargetGraphCreationResult> versionedTargetGraph = Optional.empty();
    try {
      if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
        versionedTargetGraph = Optional.of(toVersionedTargetGraph(params, targetGraph));
      }
    } catch (VersionException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    ImmutableSet<BuildTargetWithOutputs> buildTargetsWithOutputs =
        buildTargets.stream()
            .map(t -> BuildTargetWithOutputs.of(t, OutputLabel.defaultLabel()))
            .collect(ImmutableSet.toImmutableSet());

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.of(
            targetGraph,
            versionedTargetGraph,
            ActionGraphAndBuilder.of(
                new ActionGraph(actionGraphBuilder.getBuildRules()), actionGraphBuilder));

    return ImmutableGraphsAndBuildTargets.ofImpl(actionAndTargetGraphs, buildTargetsWithOutputs);
  }
}
