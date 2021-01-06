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

package com.facebook.buck.core.rules.transformer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.ProviderCreationContext;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.LegacyProviderInfoCollectionImpl;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.google.common.base.Preconditions;

public interface TargetNodeToBuildRuleTransformer {

  /** Transform target node to build rule. */
  default <T extends BuildRuleArg> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode,
      CellPathResolver cellPathResolver) {
    Preconditions.checkArgument(
        targetNode.getBuildTarget().getCell() == cellPathResolver.getCurrentCellName());

    return transform(
        toolchainProvider,
        targetGraph,
        configurationRuleRegistry,
        graphBuilder,
        targetNode,
        LegacyProviderInfoCollectionImpl.of(),
        cellPathResolver);
  }

  /**
   * Converts a given {@link TargetNode} to a {@link BuildRule} as part of action graph
   * construction. The providers computed from {@link
   * com.facebook.buck.core.rules.LegacyProviderCompatibleDescription#createProviders(ProviderCreationContext,
   * BuildTarget, BuildRuleArg)} will be passed to this function to be used to construct the {@link
   * BuildRule}
   */
  <T extends BuildRuleArg> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ConfigurationRuleRegistry configurationRuleRegistry,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode,
      ProviderInfoCollection providerInfoCollection,
      CellPathResolver cellPathResolver);
}
