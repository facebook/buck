/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A lookup table of {@link TargetNode}s which matches {@link BuildTarget} lookups to nodes indexed
 * by a base target which contains a subset of the input targets flavors. It is expected that every
 * lookup should match at most one base target.
 */
@Value.Immutable
@BuckStyleTuple
@SuppressWarnings(
    "rawtypes") // https://github.com/immutables/immutables/issues/548 requires us to use TargetNode
                // not TargetNode<?, ?>
abstract class AbstractFlavorSearchTargetNodeFinder {

  /** @return a map of nodes indexed by their "base" target. */
  abstract ImmutableMap<BuildTarget, TargetNode> getBaseTargetIndex();

  /** Verify that base targets correctly correspond to their node. */
  @Value.Check
  void check() {
    for (Map.Entry<BuildTarget, TargetNode> ent : getBaseTargetIndex().entrySet()) {
      Preconditions.checkArgument(
          ent.getValue()
              .getBuildTarget()
              .getUnflavoredBuildTarget()
              .equals(ent.getKey().getUnflavoredBuildTarget()));
      Preconditions.checkArgument(
          ent.getValue().getBuildTarget().getFlavors().containsAll(ent.getKey().getFlavors()));
    }
  }

  /**
   * @return an index from the original node target to node. Used as an optimization to avoid a more
   *     expensive flavor-subset-based lookup if we can find the node by the exact name.
   */
  @Value.Derived
  ImmutableMap<BuildTarget, TargetNode> getBuildTargetIndex() {
    return getBaseTargetIndex()
        .values()
        .stream()
        .collect(ImmutableMap.toImmutableMap(TargetNode::getBuildTarget, n -> n));
  }

  // Build the flavor map, which maps all unflavored targets to the flavors they have in the
  // graph.  We sort the list of flavor sets from largest to smallest, so that look ups pick the
  // more flavored sets first.
  @Value.Derived
  ImmutableMap<UnflavoredBuildTarget, ImmutableSet<ImmutableSet<Flavor>>> getBaseTargetFlavorMap() {
    Map<UnflavoredBuildTarget, List<ImmutableSet<Flavor>>> flavorMapRawBuilder =
        new LinkedHashMap<>();
    for (Map.Entry<BuildTarget, TargetNode> ent : getBaseTargetIndex().entrySet()) {
      BuildTarget baseTarget = ent.getKey();
      UnflavoredBuildTarget unflavoredTarget = baseTarget.getUnflavoredBuildTarget();
      if (!flavorMapRawBuilder.containsKey(unflavoredTarget)) {
        flavorMapRawBuilder.put(unflavoredTarget, new ArrayList<>());
      }
      flavorMapRawBuilder.get(unflavoredTarget).add(baseTarget.getFlavors());
    }
    ImmutableMap.Builder<UnflavoredBuildTarget, ImmutableSet<ImmutableSet<Flavor>>>
        flavorMapBuilder = ImmutableMap.builder();
    for (Map.Entry<UnflavoredBuildTarget, List<ImmutableSet<Flavor>>> ent :
        flavorMapRawBuilder.entrySet()) {
      ent.getValue().sort((o1, o2) -> Integer.compare(o2.size(), o1.size()));
      flavorMapBuilder.put(ent.getKey(), ImmutableSet.copyOf(ent.getValue()));
    }
    return flavorMapBuilder.build();
  }

  public Optional<TargetNode<?, ?>> get(BuildTarget target) {

    // If this node is in the graph under the given name, return it.
    TargetNode<?, ?> node = getBuildTargetIndex().get(target);
    if (node != null) {
      return Optional.of(node);
    }

    ImmutableSet<ImmutableSet<Flavor>> flavorList =
        getBaseTargetFlavorMap().get(target.getUnflavoredBuildTarget());
    if (flavorList == null) {
      return Optional.empty();
    }

    // Otherwise, see if this node exists in the graph with a "less" flavored name.  We initially
    // select all targets which contain a subset of the original flavors, which should be sorted by
    // from largest flavor set to smallest.  We then use the first match, and verify the subsequent
    // matches are subsets.
    ImmutableList<ImmutableSet<Flavor>> matches =
        RichStream.from(flavorList).filter(target.getFlavors()::containsAll).toImmutableList();
    if (!matches.isEmpty()) {
      ImmutableSet<Flavor> firstMatch = matches.get(0);
      for (ImmutableSet<Flavor> subsequentMatch : matches.subList(1, matches.size())) {
        Preconditions.checkState(
            firstMatch.size() > subsequentMatch.size(),
            "Expected to find larger flavor lists earlier in the flavor map "
                + "index (sizeof(%s) <= sizeof(%s))",
            firstMatch.size(),
            subsequentMatch.size());
        Preconditions.checkState(
            firstMatch.containsAll(subsequentMatch),
            "Found multiple disjoint flavor matches for %s: %s and %s (from %s)",
            target,
            firstMatch,
            subsequentMatch,
            matches);
      }
      return Optional.of(
          Preconditions.checkNotNull(
              getBaseTargetIndex().get(target.withFlavors(firstMatch)),
              "%s missing in index",
              target.withFlavors(firstMatch)));
    }

    // Otherwise, return `null` to indicate this node isn't in the target graph.
    return Optional.empty();
  }
}
