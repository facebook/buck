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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.LegacyProviderCompatibleDescription;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.LegacyProviderInfoCollectionImpl;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * The {@link GraphComputation} for performing the target graph to provider and action graph
 * transformation, with legacy compatible behaviour where we delegate to the {@link
 * com.facebook.buck.core.rules.LegacyProviderCompatibleDescription#createProviders(ProviderCreationContext,
 * BuildTarget, BuildRuleArg)}
 */
public class LegacyCompatibleRuleAnalysisComputation
    implements GraphComputation<RuleAnalysisKey, RuleAnalysisResult> {

  private final RuleAnalysisComputation delegate;
  private final TargetGraph targetGraph;

  public LegacyCompatibleRuleAnalysisComputation(
      RuleAnalysisComputation delegate, TargetGraph targetGraph) {
    this.delegate = delegate;
    this.targetGraph = targetGraph;
  }

  @Override
  public ComputationIdentifier<RuleAnalysisResult> getIdentifier() {
    return RuleAnalysisKey.IDENTIFIER;
  }

  @Override
  public RuleAnalysisResult transform(RuleAnalysisKey key, ComputationEnvironment env)
      throws ActionCreationException, RuleAnalysisException {
    return transformImpl(key, targetGraph.get(key.getBuildTarget()).cast(BuildRuleArg.class), env);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      RuleAnalysisKey key, ComputationEnvironment env) {
    if (!isCompatibleDescription(targetGraph.get(key.getBuildTarget()).getDescription())) {
      return ImmutableSet.of();
    }
    return delegate.discoverDeps(key, env);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      RuleAnalysisKey key) {
    if (!isCompatibleDescription(targetGraph.get(key.getBuildTarget()).getDescription())) {
      return ImmutableSet.of();
    }
    return delegate.discoverPreliminaryDeps(key);
  }

  private <T extends BuildRuleArg> RuleAnalysisResult transformImpl(
      RuleAnalysisKey key, TargetNode<T> targetNode, ComputationEnvironment env)
      throws ActionCreationException, RuleAnalysisException {
    BaseDescription<T> description = targetNode.getDescription();
    if (description instanceof RuleDescription) {
      return delegate.transform(key, env);
    } else if (description instanceof LegacyProviderCompatibleDescription) {
      return computeLegacyProviders(
          key, env, targetNode, (LegacyProviderCompatibleDescription<T>) description);
    } else if (description instanceof DescriptionWithTargetGraph) {
      return ImmutableLegacyProviderRuleAnalysisResult.of(
          key.getBuildTarget(), LegacyProviderInfoCollectionImpl.of());
    }

    throw new IllegalStateException(
        String.format("Unknown Description type %s", description.getClass()));
  }

  private <T extends BuildRuleArg> RuleAnalysisResult computeLegacyProviders(
      RuleAnalysisKey key,
      ComputationEnvironment env,
      TargetNode<T> targetNode,
      LegacyProviderCompatibleDescription<T> description) {
    ProviderInfoCollection providerInfoCollection =
        description.createProviders(
            ProviderCreationContext.of(
                env.getDeps(RuleAnalysisKey.IDENTIFIER).values().stream()
                    .collect(
                        ImmutableMap.toImmutableMap(
                            RuleAnalysisResult::getBuildTarget,
                            RuleAnalysisResult::getProviderInfos)),
                targetNode.getFilesystem()),
            key.getBuildTarget(),
            targetNode.getConstructorArg());

    return ImmutableLegacyProviderRuleAnalysisResult.of(
        key.getBuildTarget(), providerInfoCollection);
  }

  private boolean isCompatibleDescription(BaseDescription<?> description) {
    return description instanceof RuleDescription
        || description instanceof LegacyProviderCompatibleDescription;
  }
}
