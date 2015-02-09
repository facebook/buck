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

package com.facebook.buck.cli;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.google.common.collect.ImmutableSet;

class FetchTargetNodeToBuildRuleTransformer implements TargetNodeToBuildRuleTransformer {

  private final ImmutableSet<Description<?>> descriptions;
  // TODO(user): Allow the TargetToActionGraph to be stateless.
  private final ImmutableSet.Builder<BuildTarget> downloadableTargets;
  private final BuildTargetNodeToBuildRuleTransformer delegate;

  public FetchTargetNodeToBuildRuleTransformer(
      ImmutableSet<Description<?>> descriptions) {
    this.descriptions = descriptions;

    this.downloadableTargets = ImmutableSet.builder();
    this.delegate = new BuildTargetNodeToBuildRuleTransformer();
  }

  @Override
  public <T> BuildRule transform(
      TargetGraph targetGraph,
      BuildRuleResolver ruleResolver,
      TargetNode<T> targetNode) throws NoSuchBuildTargetException {
    TargetNode<?> node = substituteTargetNodeIfNecessary(targetNode);
    return delegate.transform(targetGraph, ruleResolver, node);
  }

  public ImmutableSet<BuildTarget> getDownloadableTargets() {
    return downloadableTargets.build();
  }

  private TargetNode<?> substituteTargetNodeIfNecessary(TargetNode<?> node) {
    for (Description<?> description : descriptions) {
      if (node.getDescription().getBuildRuleType().equals(description.getBuildRuleType())) {
        downloadableTargets.add(node.getBuildTarget());
        return node.withDescription(description);
      }
    }
    return node;
  }
}
