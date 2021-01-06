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
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.rules.query.QueryCache;
import com.facebook.buck.rules.query.QueryUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import java.util.Set;

/** Takes in an {@link TargetNode} from the target graph and builds a {@link BuildRule}. */
public class DefaultTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {
  private final QueryCache cache;

  public DefaultTargetNodeToBuildRuleTransformer() {
    cache = new QueryCache();
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

    try {
      Preconditions.checkState(
          targetNode.getDescription() instanceof DescriptionWithTargetGraph,
          "Invalid type of target node description: %s",
          targetNode.getDescription().getClass());
      DescriptionWithTargetGraph<T> description =
          (DescriptionWithTargetGraph<T>) targetNode.getDescription();
      T arg = targetNode.getConstructorArg();

      Set<BuildTarget> extraDeps = targetNode.getExtraDeps();
      Set<BuildTarget> targetGraphOnlyDeps = targetNode.getTargetGraphOnlyDeps();

      arg =
          QueryUtils.withDepsQuery(
              arg,
              targetNode.getBuildTarget(),
              cache,
              graphBuilder,
              cellPathResolver.getCellNameResolver(),
              targetGraph);
      arg =
          QueryUtils.withProvidedDepsQuery(
              arg,
              targetNode.getBuildTarget(),
              cache,
              graphBuilder,
              cellPathResolver.getCellNameResolver(),
              targetGraph);
      arg =
          QueryUtils.withModuleBlacklistQuery(
              arg,
              targetNode.getBuildTarget(),
              cache,
              graphBuilder,
              cellPathResolver.getCellNameResolver(),
              targetGraph);

      // The params used for the Buildable only contain the declared parameters. However, the deps
      // of the rule include not only those, but also any that were picked up through the deps
      // declared via a SourcePath.
      BuildRuleParams params =
          new BuildRuleParams(
              Suppliers.ofInstance(graphBuilder.requireAllRules(targetNode.getDeclaredDeps())),
              Suppliers.ofInstance(graphBuilder.requireAllRules(extraDeps)),
              graphBuilder.requireAllRules(targetGraphOnlyDeps));

      BuildRuleCreationContextWithTargetGraph context =
          BuildRuleCreationContextWithTargetGraph.of(
              targetGraph,
              graphBuilder,
              targetNode.getFilesystem(),
              cellPathResolver,
              toolchainProvider,
              configurationRuleRegistry,
              providerInfoCollection);

      return description.createBuildRule(context, targetNode.getBuildTarget(), params, arg);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When creating rule %s.", targetNode.getBuildTarget());
    }
  }
}
