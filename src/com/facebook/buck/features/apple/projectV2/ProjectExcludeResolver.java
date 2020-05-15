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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class ProjectExcludeResolver {

  private final TargetGraph graph;
  private final ImmutableList<String> excludedLabels;

  ProjectExcludeResolver(TargetGraph graph, ImmutableList<String> excludedLabels) {
    this.graph = graph;
    this.excludedLabels = excludedLabels;
  }

  boolean excludeTarget(BuildTarget target) {
    Optional<TargetNode<?>> optionalNode = graph.getOptional(target);
    if (optionalNode.isPresent()) {
      TargetNode<?> node = optionalNode.get();
      if (node.getConstructorArg() instanceof BuildRuleArg) {
        BuildRuleArg buildRuleArg = (BuildRuleArg) node.getConstructorArg();
        return excludeBuildRuleArg(buildRuleArg);
      }
    }
    return false;
  }

  boolean excludeBuildRuleArg(BuildRuleArg buildRuleArg) {
    return excludedLabels.stream().anyMatch(label -> buildRuleArg.getLabels().contains(label));
  }
}
