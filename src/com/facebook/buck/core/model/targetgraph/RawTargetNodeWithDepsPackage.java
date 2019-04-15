/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

/** Represents all {@link RawTargetNodeWithDeps} that result from parsing a single build file */
@Value.Immutable(builder = false, copy = false)
@JsonDeserialize
public abstract class RawTargetNodeWithDepsPackage implements ComputeResult {

  /**
   * All {@link RawTargetNodeWithDeps} which comes from the same build package, i.e. result from
   * parsing a single build file. Key is a string representing short build target name (last part of
   * the name after the colon) and value is corresponding target.
   */
  @Value.Parameter
  @JsonProperty("nodes")
  public abstract ImmutableMap<String, RawTargetNodeWithDeps> getRawTargetNodesWithDeps();
}
