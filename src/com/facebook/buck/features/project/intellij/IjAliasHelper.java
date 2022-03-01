/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.alias.AbstractAliasDescription;

/** Help class to deal with Alias rules */
public class IjAliasHelper {
  private IjAliasHelper() {}

  public static boolean isAliasNode(TargetNode<?> targetNode) {
    return targetNode.getDescription() instanceof AbstractAliasDescription;
  }

  /** Recursively resolve the targetNode to its actual node if it is an alias */
  @SuppressWarnings("unchecked")
  public static TargetNode<?> resolveAliasNode(TargetGraph targetGraph, TargetNode<?> targetNode) {
    if (!isAliasNode(targetNode)) {
      return targetNode;
    }
    // Use BuildRuleArg so that we can handle both Alias and ConfiguredAlias
    TargetNode<BuildRuleArg> node = (TargetNode<BuildRuleArg>) targetNode;
    AbstractAliasDescription<BuildRuleArg> description =
        (AbstractAliasDescription<BuildRuleArg>) node.getDescription();
    return resolveAliasNode(
        targetGraph,
        targetGraph.get(description.resolveActualBuildTarget(node.getConstructorArg())));
  }
}
