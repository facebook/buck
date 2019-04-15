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

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A collection of {@link GraphComputationStage} offering retrieval of stages via key class with
 * casting of types
 */
class ComputationStageMap {

  private final ImmutableMap<Class<? extends ComputeKey<?>>, GraphComputationStage<?, ?>> stageMaps;

  ComputationStageMap(
      ImmutableMap<Class<? extends ComputeKey<?>>, GraphComputationStage<?, ?>> stageMaps) {
    this.stageMaps = stageMaps;
  }

  static ComputationStageMap from(ImmutableList<GraphComputationStage<?, ?>> stages) {
    ImmutableMap.Builder<Class<? extends ComputeKey<?>>, GraphComputationStage<?, ?>> mapBuilder =
        ImmutableMap.builderWithExpectedSize(stages.size());
    for (GraphComputationStage<?, ?> stage : stages) {
      mapBuilder.put(stage.getKeyClass(), stage);
    }
    return new ComputationStageMap(mapBuilder.build());
  }

  @SuppressWarnings("unchecked")
  /** @return the corresponding {@link GraphComputationStage} for the given {@link ComputeKey} */
  GraphComputationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult> get(
      ComputeKey<? extends ComputeResult> key) {
    GraphComputationStage<?, ?> ret = stageMaps.get(key.getKeyClass());
    Verify.verify(ret != null, "Unknown stage for key: (%s) requested.", key);
    return (GraphComputationStage<ComputeKey<? extends ComputeResult>, ? extends ComputeResult>)
        ret;
  }
}
