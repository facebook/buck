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

package com.facebook.buck.core.rules.transformer.impl;

import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableSet;

public class FetchTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {

  private final ImmutableSet<DescriptionWithTargetGraph<?>> descriptions;
  // TODO(simons): Allow the TargetToActionGraph to be stateless.
  private final ImmutableSet.Builder<BuildTarget> downloadableTargets;
  private final DefaultTargetNodeToBuildRuleTransformer delegate;

  public FetchTargetNodeToBuildRuleTransformer(
      ImmutableSet<DescriptionWithTargetGraph<?>> descriptions) {
    this.descriptions = descriptions;

    this.downloadableTargets = ImmutableSet.builder();
    this.delegate = new DefaultTargetNodeToBuildRuleTransformer();
  }

  @Override
  public <T> BuildRule transform(
      ToolchainProvider toolchainProvider,
      TargetGraph targetGraph,
      ActionGraphBuilder graphBuilder,
      TargetNode<T> targetNode) {
    TargetNode<?> node = substituteTargetNodeIfNecessary(targetNode);
    return delegate.transform(toolchainProvider, targetGraph, graphBuilder, node);
  }

  public ImmutableSet<BuildTarget> getDownloadableTargets() {
    return downloadableTargets.build();
  }

  private TargetNode<?> substituteTargetNodeIfNecessary(TargetNode<?> node) {
    for (DescriptionWithTargetGraph<?> description : descriptions) {
      if (node.getRuleType().equals(DescriptionCache.getRuleType(description))) {
        downloadableTargets.add(node.getBuildTarget());
        return node.getNodeCopier().copyNodeWithDescription(node.copy(), description);
      }
    }
    return node;
  }
}
