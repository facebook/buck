/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.ImmutableBuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Generates build rules as if they have no dependencies, so that action graph generation visits
 * each target node only once. This produces a sufficiently complete action graph to generate .iml
 * files.
 */
public class ShallowTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {

  public ShallowTargetNodeToBuildRuleTransformer() {}

  @Override
  public <T, U extends DescriptionWithTargetGraph<T>> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode) {
    DescriptionWithTargetGraph<T> description =
        (DescriptionWithTargetGraph<T>) targetNode.getDescription();
    T arg = targetNode.getConstructorArg();

    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());

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
