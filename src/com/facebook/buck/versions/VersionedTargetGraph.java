/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGroup;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public class VersionedTargetGraph extends TargetGraph {

  private final ImmutableMap<BuildTarget, TargetNode<?, ?>> targetsToNodes;
  private final ImmutableMap<UnflavoredBuildTarget, ImmutableList<ImmutableSet<Flavor>>> flavorMap;

  public VersionedTargetGraph(
      MutableDirectedGraph<TargetNode<?, ?>> graph,
      ImmutableMap<BuildTarget, TargetNode<?, ?>> index,
      ImmutableSet<TargetGroup> groups) {
    super(graph, index, groups);

    this.targetsToNodes = index;

    // Build the flavor map, which maps all unflavored targets to the flavors they have in the
    // graph.  We sort the list of flavor sets from largest to smallest, so that look ups pick the
    // more flavored sets first.
    Map<UnflavoredBuildTarget, List<ImmutableSet<Flavor>>> flavorMapRawBuilder =
        new LinkedHashMap<>();
    for (BuildTarget target : index.keySet()) {
      UnflavoredBuildTarget unflavoredTarget = target.getUnflavoredBuildTarget();
      if (!flavorMapRawBuilder.containsKey(unflavoredTarget)) {
        flavorMapRawBuilder.put(unflavoredTarget, new ArrayList<>());
      }
      flavorMapRawBuilder.get(unflavoredTarget).add(target.getFlavors());
    }
    ImmutableMap.Builder<UnflavoredBuildTarget, ImmutableList<ImmutableSet<Flavor>>>
        flavorMapBuilder = ImmutableMap.builder();
    for (Map.Entry<UnflavoredBuildTarget, List<ImmutableSet<Flavor>>> ent :
        flavorMapRawBuilder.entrySet()) {
      ent.getValue().sort((o1, o2) -> Integer.compare(o2.size(), o1.size()));
      flavorMapBuilder.put(ent.getKey(), ImmutableList.copyOf(ent.getValue()));
    }
    this.flavorMap = flavorMapBuilder.build();
  }

  @Nullable
  @Override
  protected TargetNode<?, ?> getInternal(BuildTarget target) {

    // If this node is in the graph under the given name, return it.
    TargetNode<?, ?> node = targetsToNodes.get(target);
    if (node != null) {
      return node;
    }

    ImmutableList<ImmutableSet<Flavor>> flavorList =
        flavorMap.get(target.getUnflavoredBuildTarget());
    if (flavorList == null) {
      return null;
    }

    // Otherwise, see if this node exists in the graph with a "less" flavored name.  We initially
    // select all targets which contain a subset of the original flavors, which should be sorted by
    // from largest flavor set to smallest.  We then use the first match, and verify the subsequent
    // matches are subsets.
    ImmutableList<ImmutableSet<Flavor>> matches =
        RichStream.from(flavorList)
            .filter(target.getFlavors()::containsAll)
            .toImmutableList();
    if (!matches.isEmpty()) {
      ImmutableSet<Flavor> firstMatch = matches.get(0);
      for (ImmutableSet<Flavor> subsequentMatch : matches.subList(1, matches.size())) {
        Preconditions.checkState(firstMatch.size() > subsequentMatch.size());
        Preconditions.checkState(
            firstMatch.containsAll(subsequentMatch),
            "Found multiple disjoint flavor matches for %s: %s and %s",
            target.getUnflavoredBuildTarget(),
            firstMatch,
            subsequentMatch);
      }
      return Preconditions.checkNotNull(targetsToNodes.get(target.withFlavors(firstMatch)))
          .withFlavors(target.getFlavors());
    }

    // Otherwise, return `null` to indicate this node isn't in the target graph.
    return null;
  }

}
