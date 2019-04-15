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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import org.immutables.value.Value;

/** Transformation key containing build target to get {@link RawTargetNode} for. */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class BuildTargetToRawTargetNodeKey implements ComputeKey<RawTargetNode> {

  /** Build target that uniquely identifies {@link RawTargetNode} */
  @Value.Parameter
  public abstract UnconfiguredBuildTarget getBuildTarget();

  @Override
  public Class<? extends ComputeKey<?>> getKeyClass() {
    return BuildTargetToRawTargetNodeKey.class;
  }
}
