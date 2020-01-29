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

package com.facebook.buck.core.model.targetgraph.raw;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableSet;

/** A pair of {@link UnconfiguredTargetNode} and its dependencies */
@BuckStyleValue
@JsonDeserialize
public abstract class UnconfiguredTargetNodeWithDeps implements ComputeResult {

  /** Raw target node, i.e. a target node with partially resolved attributes */
  @JsonProperty("node")
  public abstract UnconfiguredTargetNode getUnconfiguredTargetNode();

  /**
   * List of build targets that this node depends on. Because {@link UnconfiguredTargetNode} may
   * have unresolved configuration, this list is excessive, i.e. may contain all possible dependents
   * for all possible configurations.
   */
  @JsonProperty("deps")
  public abstract ImmutableSet<UnconfiguredBuildTarget> getDeps();

  /**
   * This mixin is used by JSON serializer to flatten {@link UnconfiguredTargetNode} properties We
   * cannot use {@link JsonUnwrapped} directly on {@link
   * UnconfiguredTargetNodeWithDeps#getUnconfiguredTargetNode()} because it is not supported by
   * typed deserializer
   */
  public interface UnconfiguredTargetNodeWithDepsUnwrappedMixin {
    @JsonUnwrapped
    UnconfiguredTargetNode getUnconfiguredTargetNode();
  }

  public static UnconfiguredTargetNodeWithDeps of(
      UnconfiguredTargetNode unconfiguredTargetNode, ImmutableSet<UnconfiguredBuildTarget> deps) {
    return ImmutableUnconfiguredTargetNodeWithDeps.of(unconfiguredTargetNode, deps);
  }
}
