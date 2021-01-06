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

package com.facebook.buck.core.graph.transformation.impl;

import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
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

  private final ImmutableMap<ComputationIdentifier<?>, GraphComputationStage<?, ?>> stageMaps;

  ComputationStageMap(
      ImmutableMap<ComputationIdentifier<?>, GraphComputationStage<?, ?>> stageMaps) {
    this.stageMaps = stageMaps;
  }

  static ComputationStageMap from(ImmutableList<GraphComputationStage<?, ?>> stages) {
    ImmutableMap.Builder<ComputationIdentifier<?>, GraphComputationStage<?, ?>> mapBuilder =
        ImmutableMap.builderWithExpectedSize(stages.size());
    for (GraphComputationStage<?, ?> stage : stages) {
      mapBuilder.put(stage.getIdentifier(), stage);
    }
    return new ComputationStageMap(mapBuilder.build());
  }

  @SuppressWarnings("unchecked")
  /** @return the corresponding {@link GraphComputationStage} for the given {@link ComputeKey} */
  <UResultType extends ComputeResult, UKeyType extends ComputeKey<UResultType>>
      GraphComputationStage<UKeyType, UResultType> get(UKeyType key) {
    GraphComputationStage<?, ?> ret = stageMaps.get(key.getIdentifier());
    Verify.verify(ret != null, "Unknown stage for key: (%s) requested.", key);
    return (GraphComputationStage<UKeyType, UResultType>) ret;
  }
}
