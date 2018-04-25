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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

public class VersionedTargetGraphFactory {

  private VersionedTargetGraphFactory() {}

  public static VersionedTargetGraph newInstance(
      ImmutableMap<BuildTarget, TargetNode<?, ?>> index) {
    VersionedTargetGraph.Builder builder = VersionedTargetGraph.builder();
    for (Map.Entry<BuildTarget, TargetNode<?, ?>> ent : index.entrySet()) {
      builder.addNode(ent.getKey(), ent.getValue());
      for (BuildTarget dep : ent.getValue().getBuildDeps()) {
        builder.addEdge(ent.getValue(), Preconditions.checkNotNull(index.get(dep), dep));
      }
    }
    return builder.build();
  }

  public static VersionedTargetGraph newInstance(ImmutableList<TargetNode<?, ?>> nodes) {
    return newInstance(
        nodes.stream().collect(ImmutableMap.toImmutableMap(TargetNode::getBuildTarget, n -> n)));
  }
}
