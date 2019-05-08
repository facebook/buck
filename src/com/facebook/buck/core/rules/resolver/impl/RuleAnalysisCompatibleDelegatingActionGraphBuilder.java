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
package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisComputation;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.transformer.impl.LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An {@link ActionGraphBuilder} that manages both rule analysis computation and the legacy action
 * graph construction.
 *
 * <p>This builder delegates to the new {@link RuleAnalysisComputation} and {@link
 * ActionGraphBuilder} as appropriate depending on the rule that is requested.
 *
 * <p>It satisfies the API of both computations to provide compatibility between them
 */
public class RuleAnalysisCompatibleDelegatingActionGraphBuilder extends AbstractActionGraphBuilder
    implements RuleAnalysisComputation {
  // TODO(bobyf): allow rule analysis computation to access action graph rules

  private final ActionGraphBuilder delegateActionGraphBuilder;
  private final RuleAnalysisComputation delegateRuleAnalysisComputation;

  public RuleAnalysisCompatibleDelegatingActionGraphBuilder(
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      Function<TargetNodeToBuildRuleTransformer, ActionGraphBuilder> delegateBuilderConstructor,
      RuleAnalysisComputation delegateRuleAnalysisComputation) {
    delegateActionGraphBuilder =
        delegateBuilderConstructor.apply(
            new LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer(
                this, buildRuleGenerator));
    this.delegateRuleAnalysisComputation = delegateRuleAnalysisComputation;
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    return delegateActionGraphBuilder.getBuildRules();
  }

  @Override
  public BuildRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, BuildRule> mappingFunction) {
    return delegateActionGraphBuilder.computeIfAbsent(target, mappingFunction);
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    return delegateActionGraphBuilder.requireRule(target);
  }

  @Override
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    return delegateActionGraphBuilder.requireMetadata(target, metadataClass);
  }

  @Override
  @Deprecated
  public <T extends BuildRule> T addToIndex(T buildRule) {
    return delegateActionGraphBuilder.addToIndex(buildRule);
  }

  @Override
  public Parallelizer getParallelizer() {
    return delegateActionGraphBuilder.getParallelizer();
  }

  @Override
  public void invalidate() {
    delegateActionGraphBuilder.invalidate();
  }

  @Override
  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    return delegateActionGraphBuilder.getRuleOptional(buildTarget);
  }

  @Override
  public RuleAnalysisResult computeUnchecked(RuleAnalysisKey lookupKey) {
    return delegateRuleAnalysisComputation.computeUnchecked(lookupKey);
  }

  @Override
  public ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> computeAllUnchecked(
      Set<RuleAnalysisKey> lookupKeys) {
    return delegateRuleAnalysisComputation.computeAllUnchecked(lookupKeys);
  }
}
