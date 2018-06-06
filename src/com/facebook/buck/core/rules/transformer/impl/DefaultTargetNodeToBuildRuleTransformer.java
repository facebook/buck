/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.ImmutableBuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.query.QueryCache;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.base.Suppliers;
import java.util.Set;

/** Takes in an {@link TargetNode} from the target graph and builds a {@link BuildRule}. */
public class DefaultTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {
  private final QueryCache cache;

  public DefaultTargetNodeToBuildRuleTransformer() {
    cache = new QueryCache();
  }

  @Override
  public <T, U extends DescriptionWithTargetGraph<T>> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ActionGraphBuilder graphBuilder,
      TargetNode<T, U> targetNode) {
    U description = targetNode.getDescription();
    T arg = targetNode.getConstructorArg();

    Set<BuildTarget> extraDeps = targetNode.getExtraDeps();
    Set<BuildTarget> targetGraphOnlyDeps = targetNode.getTargetGraphOnlyDeps();

    arg =
        QueryUtils.withDepsQuery(
            arg,
            targetNode.getBuildTarget(),
            cache,
            graphBuilder,
            targetNode.getCellNames(),
            targetGraph);
    arg =
        QueryUtils.withProvidedDepsQuery(
            arg,
            targetNode.getBuildTarget(),
            cache,
            graphBuilder,
            targetNode.getCellNames(),
            targetGraph);

    // The params used for the Buildable only contain the declared parameters. However, the deps of
    // the rule include not only those, but also any that were picked up through the deps declared
    // via a SourcePath.
    BuildRuleParams params =
        new BuildRuleParams(
            Suppliers.ofInstance(graphBuilder.requireAllRules(targetNode.getDeclaredDeps())),
            Suppliers.ofInstance(graphBuilder.requireAllRules(extraDeps)),
            graphBuilder.requireAllRules(targetGraphOnlyDeps));

    BuildRuleCreationContextWithTargetGraph context =
        ImmutableBuildRuleCreationContextWithTargetGraph.of(
            targetGraph,
            graphBuilder,
            targetNode.getFilesystem(),
            targetNode.getCellNames(),
            toolchainProvider);

    return description.createBuildRule(context, targetNode.getBuildTarget(), params, arg);
  }
}
