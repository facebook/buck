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
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph;
import com.facebook.buck.core.rules.analysis.impl.LegacyProviderRuleAnalysisResult;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.LegacyProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;

/**
 * A {@link TargetNodeToBuildRuleTransformer} that delegates to the {@link RuleAnalysisGraph} when
 * descriptions of the new type {@link RuleDescription} is encountered. A backwards compatible
 * {@link RuleAnalysisLegacyBuildRuleView} is returned for that target.
 *
 * <p>For any legacy rules, the {@link com.facebook.buck.core.rules.providers.Provider}s from {@link
 * com.facebook.buck.core.rules.LegacyProviderCompatibleDescription#createProviders(ProviderCreationContext,
 * BuildTarget, BuildRuleArg)} will be supplied to the {@link
 * com.facebook.buck.core.rules.DescriptionWithTargetGraph#createBuildRule(BuildRuleCreationContextWithTargetGraph,
 * BuildTarget, BuildRuleParams, BuildRuleArg)}
 */
public class LegacyRuleAnalysisProviderCompatibleTargetNodeToBuildRuleTransformer
    extends LegacyRuleAnalysisDelegatingTargetNodeToBuildRuleTransformer {

  public LegacyRuleAnalysisProviderCompatibleTargetNodeToBuildRuleTransformer(
      RuleAnalysisGraph ruleAnalysisComputation, TargetNodeToBuildRuleTransformer delegate) {
    super(ruleAnalysisComputation, delegate);
  }

  @Override
  public <T extends BuildRuleArg> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode,
      CellPathResolver cellPathResolver) {
    Preconditions.checkArgument(
        targetNode.getBuildTarget().getCell() == cellPathResolver.getCurrentCellName());

    BaseDescription<T> description = targetNode.getDescription();

    ProviderInfoCollection providerInfos;

    if (description instanceof DescriptionWithTargetGraph) {
      RuleAnalysisResult result =
          ruleAnalysisComputation.get(RuleAnalysisKey.of(targetNode.getBuildTarget()));
      // check that we are getting legacy providers. More or a sanity check than a necessary check
      Verify.verify(result instanceof LegacyProviderRuleAnalysisResult);
      providerInfos = result.getProviderInfos();
    } else {
      providerInfos = LegacyProviderInfoCollectionImpl.of();
    }

    return transform(
        toolchainProvider,
        targetGraph,
        configurationRuleRegistry,
        graphBuilder,
        targetNode,
        providerInfos,
        cellPathResolver);
  }
}
