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

package com.facebook.buck.core.rules.transformer.impl;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.RuleDescriptionWithInstanceName;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.providers.lib.TestInfo;
import com.facebook.buck.core.rules.tool.RuleAnalysisLegacyBinaryBuildRuleView;
import com.facebook.buck.core.rules.tool.RuleAnalysisLegacyTestBuildRuleView;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import java.util.Optional;

/**
 * A {@link TargetNodeToBuildRuleTransformer} that delegates to the {@link RuleAnalysisGraph} when
 * descriptions of the new type {@link RuleDescription} is encountered. A backwards compatible
 * {@link RuleAnalysisLegacyBuildRuleView} is returned for that target.
 */
public class LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer
    implements TargetNodeToBuildRuleTransformer {

  protected final RuleAnalysisGraph ruleAnalysisComputation;
  protected final TargetNodeToBuildRuleTransformer delegate;

  public LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer(
      RuleAnalysisGraph ruleAnalysisComputation, TargetNodeToBuildRuleTransformer delegate) {
    this.ruleAnalysisComputation = ruleAnalysisComputation;
    this.delegate = delegate;
  }

  @Override
  public <T extends BuildRuleArg> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode,
      ProviderInfoCollection providerInfoCollection,
      CellPathResolver cellPathResolver) {
    Preconditions.checkArgument(
        targetNode.getBuildTarget().getCell() == cellPathResolver.getCurrentCellName());

    BaseDescription<T> description = targetNode.getDescription();
    if (description instanceof RuleDescription) {
      RuleAnalysisResult result =
          ruleAnalysisComputation.get(RuleAnalysisKey.of(targetNode.getBuildTarget()));

      // TODO(bobyf): add support for multiple actions from a rule
      ImmutableCollection<ActionAnalysisData> actions = result.getRegisteredActions().values();

      Optional<Action> correspondingAction;
      if (actions.isEmpty()) {
        correspondingAction = Optional.empty();
      } else {
        correspondingAction =
            Optional.of(((ActionWrapperData) Iterables.getOnlyElement(actions)).getAction());
      }

      ProviderInfoCollection providerInfos = result.getProviderInfos();
      String ruleName = getRuleName(description, targetNode.getConstructorArg());
      return providerInfos
          .get(TestInfo.PROVIDER)
          .<BuildRule>map(
              testInfo ->
                  new RuleAnalysisLegacyTestBuildRuleView(
                      ruleName,
                      result.getBuildTarget(),
                      correspondingAction,
                      graphBuilder,
                      targetNode.getFilesystem(),
                      providerInfos))
          .orElseGet(
              () ->
                  providerInfos
                      .get(RunInfo.PROVIDER)
                      .<BuildRule>map(
                          runInfo ->
                              new RuleAnalysisLegacyBinaryBuildRuleView(
                                  ruleName,
                                  result.getBuildTarget(),
                                  correspondingAction,
                                  graphBuilder,
                                  targetNode.getFilesystem(),
                                  providerInfos))
                      .orElseGet(
                          () ->
                              new RuleAnalysisLegacyBuildRuleView(
                                  ruleName,
                                  result.getBuildTarget(),
                                  correspondingAction,
                                  graphBuilder,
                                  targetNode.getFilesystem(),
                                  providerInfos)));
    }

    return delegate.transform(
        toolchainProvider,
        targetGraph,
        configurationRuleRegistry,
        graphBuilder,
        targetNode,
        providerInfoCollection,
        cellPathResolver);
  }

  private static <T extends BuildRuleArg> String getRuleName(
      BaseDescription<T> baseDescription, T constructorArg) {
    if (baseDescription instanceof RuleDescriptionWithInstanceName) {
      return ((RuleDescriptionWithInstanceName<T>) baseDescription).getRuleName(constructorArg);
    } else {
      return DescriptionCache.getRuleType(baseDescription).getName();
    }
  }
}
