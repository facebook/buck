/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.transformer.impl;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionWrapperData;
import com.facebook.buck.core.rules.analysis.ImmutableRuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisComputation;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.Iterables;
import java.util.Objects;

/**
 * A {@link TargetNodeToBuildRuleTransformer} that delegates to the {@link RuleAnalysisComputation}
 * when descriptions of the new type {@link RuleDescription} is encountered. A backwards compatible
 * {@link RuleAnalysisLegacyBuildRuleView} is returned for that target.
 */
public class LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer
    implements TargetNodeToBuildRuleTransformer {

  private final RuleAnalysisComputation ruleAnalysisComputation;
  private final TargetNodeToBuildRuleTransformer delegate;

  public LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer(
      RuleAnalysisComputation ruleAnalysisComputation, TargetNodeToBuildRuleTransformer delegate) {
    this.ruleAnalysisComputation = ruleAnalysisComputation;
    this.delegate = delegate;
  }

  @Override
  public <T> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode) {
    BaseDescription<T> description = targetNode.getDescription();
    if (description instanceof RuleDescription) {
      RuleDescription<T> legacyRuleDescription = (RuleDescription<T>) description;
      RuleAnalysisResult result =
          ruleAnalysisComputation.computeUnchecked(
              ImmutableRuleAnalysisKey.of(targetNode.getBuildTarget()));

      // TODO(bobyf): add support for multiple actions from a rule
      if (result.getRegisteredActions().isEmpty()) {
        return new NoopBuildRule(result.getBuildTarget(), targetNode.getFilesystem());
      }

      Action correspondingAction =
          ((ActionWrapperData)
                  Iterables.getOnlyElement(result.getRegisteredActions().entrySet()).getValue())
              .getAction();

      graphBuilder.requireAllRules(
          RichStream.from(correspondingAction.getInputs())
              .map(artifact -> artifact.asBound().asBuildArtifact())
              .filter(Objects::nonNull)
              .map(buildArtifact -> buildArtifact.getSourcePath().getTarget())
              .toImmutableList());

      return new RuleAnalysisLegacyBuildRuleView(
          legacyRuleDescription.getConstructorArgType().getTypeName(),
          result.getBuildTarget(),
          correspondingAction,
          graphBuilder,
          targetNode.getFilesystem());
    }

    return delegate.transform(
        toolchainProvider, targetGraph, configurationRuleRegistry, graphBuilder, targetNode);
  }
}
