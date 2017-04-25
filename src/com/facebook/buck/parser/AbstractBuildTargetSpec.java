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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

/** Matches a {@link TargetNode} name by a specific {@link BuildTarget}. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractBuildTargetSpec implements TargetNodeSpec {

  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  @Override
  @Value.Parameter
  public abstract BuildFileSpec getBuildFileSpec();

  public static BuildTargetSpec from(BuildTarget target) {
    return BuildTargetSpec.of(target, BuildFileSpec.fromBuildTarget(target));
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.SINGLE_TARGET;
  }

  @Override
  public ImmutableMap<BuildTarget, Optional<TargetNode<?, ?>>> filter(
      Iterable<TargetNode<?, ?>> nodes) {
    Optional<TargetNode<?, ?>> firstMatchingNode =
        StreamSupport.stream(nodes.spliterator(), false)
            .filter(
                input ->
                    input
                        .getBuildTarget()
                        .getUnflavoredBuildTarget()
                        .equals(getBuildTarget().getUnflavoredBuildTarget()))
            .findFirst();
    return ImmutableMap.of(getBuildTarget(), firstMatchingNode);
  }

  @Override
  public String toString() {
    return getBuildTarget().getFullyQualifiedName();
  }
}
