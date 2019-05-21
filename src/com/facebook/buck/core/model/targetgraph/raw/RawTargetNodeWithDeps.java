/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph.raw;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/** A pair of {@link RawTargetNode} and its dependencies */
@Value.Immutable(builder = false, copy = false)
@JsonDeserialize
public abstract class RawTargetNodeWithDeps implements ComputeResult {

  /** Raw target node, i.e. a target node with partially resolved attributes */
  @Value.Parameter
  @JsonProperty("node")
  public abstract RawTargetNode getRawTargetNode();

  /**
   * List of build targets that this node depends on. Because {@link RawTargetNode} may have
   * unresolved configuration, this list is excessive, i.e. may contain all possible dependents for
   * all possible configurations.
   */
  @Value.Parameter
  @JsonProperty("deps")
  public abstract ImmutableSet<UnconfiguredBuildTarget> getDeps();

  public static RawTargetNodeWithDeps of(
      RawTargetNode rawTargetNode, Iterable<? extends UnconfiguredBuildTarget> deps) {
    return ImmutableRawTargetNodeWithDeps.of(rawTargetNode, deps);
  }

  /**
   * This mixin is used by JSON serializer to flatten {@link RawTargetNode} properties We cannot use
   * {@link JsonUnwrapped} directly on {@link RawTargetNodeWithDeps#getRawTargetNode()} because it
   * is not supported by typed deserializer
   */
  public interface RawTargetNodeWithDepsUnwrappedMixin {
    @JsonUnwrapped
    RawTargetNode getRawTargetNode();
  }
}
