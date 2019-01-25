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

package com.facebook.buck.parser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

/** Matches a {@link TargetNode} name by a specific {@link BuildTarget}. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractBuildTargetSpec implements TargetNodeSpec {

  @Value.Parameter
  public abstract UnconfiguredBuildTarget getUnconfiguredBuildTarget();

  @Override
  @Value.Parameter
  public abstract BuildFileSpec getBuildFileSpec();

  public static BuildTargetSpec from(UnconfiguredBuildTarget target) {
    return BuildTargetSpec.of(target, BuildFileSpec.fromUnconfiguredBuildTarget(target));
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.SINGLE_TARGET;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNode<?>> filter(Iterable<TargetNode<?>> nodes) {
    TargetNode<?> firstMatchingNode =
        StreamSupport.stream(nodes.spliterator(), false)
            .filter(
                input ->
                    input
                        .getBuildTarget()
                        .getUnflavoredBuildTarget()
                        .equals(getUnconfiguredBuildTarget().getUnflavoredBuildTarget()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Cannot find target node for build target "
                            + getUnconfiguredBuildTarget()));
    return ImmutableMap.of(firstMatchingNode.getBuildTarget(), firstMatchingNode);
  }

  @Override
  public String toString() {
    return getUnconfiguredBuildTarget().getFullyQualifiedName();
  }
}
