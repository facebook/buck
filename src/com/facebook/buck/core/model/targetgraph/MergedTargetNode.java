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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A set of target nodes with the same unconfigured build target.
 *
 * <p>This utility exists to support legacy configured {@code buck query}, and should not be used
 * for anything else.
 */
public class MergedTargetNode implements Comparable<MergedTargetNode> {
  private final UnflavoredBuildTarget buildTarget;
  private final ImmutableList<TargetNode<?>> nodes;
  private final RuleType ruleType;

  private MergedTargetNode(UnflavoredBuildTarget buildTarget, ImmutableList<TargetNode<?>> nodes) {
    Preconditions.checkArgument(!nodes.isEmpty());

    for (TargetNode<?> node : nodes) {
      Preconditions.checkArgument(
          node.getBuildTarget().getUnflavoredBuildTarget().equals(buildTarget));
    }

    this.buildTarget = buildTarget;
    this.nodes = nodes;

    // All node with the same unflavored view must have the same rule type
    this.ruleType =
        Iterators.getOnlyElement(nodes.stream().map(TargetNode::getRuleType).distinct().iterator());
  }

  public ImmutableList<TargetNode<?>> getNodes() {
    return nodes;
  }

  public TargetNode<?> getAnyNode() {
    return nodes.iterator().next();
  }

  public UnflavoredBuildTarget getBuildTarget() {
    return buildTarget;
  }

  public RuleType getRuleType() {
    return ruleType;
  }

  /** Group targets by unflavored target. */
  public static ImmutableMap<UnflavoredBuildTarget, MergedTargetNode> group(
      Collection<TargetNode<?>> targetNodes) {
    Map<UnflavoredBuildTarget, List<TargetNode<?>>> collect =
        targetNodes.stream()
            .collect(Collectors.groupingBy(t -> t.getBuildTarget().getUnflavoredBuildTarget()));
    ImmutableMap.Builder<UnflavoredBuildTarget, MergedTargetNode> builder = ImmutableMap.builder();
    for (Map.Entry<UnflavoredBuildTarget, List<TargetNode<?>>> entry : collect.entrySet()) {
      // Sort nodes to make everything deterministic
      ImmutableList<TargetNode<?>> nodes =
          entry.getValue().stream().sorted().collect(ImmutableList.toImmutableList());
      builder.put(entry.getKey(), new MergedTargetNode(entry.getKey(), nodes));
    }
    return builder.build();
  }

  public ImmutableSet<TargetConfiguration> getTargetConfigurations() {
    return nodes.stream()
        .map(t -> t.getBuildTarget().getTargetConfiguration())
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public String toString() {
    return "MergedTargetNode(" + buildTarget + ")";
  }

  @Override
  public int compareTo(MergedTargetNode that) {
    return this.buildTarget.compareTo(that.buildTarget);
  }
}
