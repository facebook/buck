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
package com.facebook.buck.core.graph.transformation;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A collection of {@link GraphTransformationStage} offering retrieval of stages via key class with
 * casting of types
 */
class TransformationStageMap {

  private final ImmutableMap<Class<? extends ComputeKey<?>>, GraphTransformationStage<?, ?>>
      stageMaps;

  TransformationStageMap(
      ImmutableMap<Class<? extends ComputeKey<?>>, GraphTransformationStage<?, ?>> stageMaps) {
    this.stageMaps = stageMaps;
  }

  static TransformationStageMap from(ImmutableList<GraphTransformationStage<?, ?>> stages) {
    ImmutableMap.Builder<Class<? extends ComputeKey<?>>, GraphTransformationStage<?, ?>>
        mapBuilder = ImmutableMap.builderWithExpectedSize(stages.size());
    for (GraphTransformationStage<?, ?> stage : stages) {
      mapBuilder.put(stage.getKeyClass(), stage);
    }
    return new TransformationStageMap(mapBuilder.build());
  }

  @SuppressWarnings("unchecked")
  /** @return the corresponding {@link GraphTransformationStage} for the given {@link ComputeKey} */
  GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult> get(
      ComputeKey<? extends ComputeResult> key) {
    GraphTransformationStage<?, ?> ret = stageMaps.get(key.getKeyClass());
    Verify.verify(ret != null, "Unknown stage for key: (%s) requested.", key);
    return (GraphTransformationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>)
        ret;
  }
}
