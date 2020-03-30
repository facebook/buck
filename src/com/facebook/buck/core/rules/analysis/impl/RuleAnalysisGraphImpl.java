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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.cache.RuleAnalysisCache;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisGraph;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * Default implementation of {@link RuleAnalysisGraph} driven by a {@link GraphTransformationEngine}
 *
 * <p>TODO(bobyf): once we add stages to {@link GraphTransformationEngine} this implementation and
 * the interface will probably change/go away
 */
public class RuleAnalysisGraphImpl implements RuleAnalysisGraph {

  private final GraphTransformationEngine computationEngine;

  private RuleAnalysisGraphImpl(GraphTransformationEngine computationEngine) {
    this.computationEngine = computationEngine;
  }

  /**
   * creates a new computation with the supplied {@link DepsAwareExecutor} and using the given
   * {@link GraphEngineCache}. All entries present in the cache will be reused, and newly computed
   * values will be offered to the cache.
   */
  public static RuleAnalysisGraphImpl of(
      TargetGraph targetGraph,
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      RuleAnalysisCache cache,
      BuckEventBus eventBus) {
    RuleAnalysisComputation transformer = new RuleAnalysisComputation(targetGraph, eventBus);
    return of(transformer, targetGraph, depsAwareExecutor, cache);
  }

  /**
   * creates a new computation with the supplied {@link DepsAwareExecutor} and using the given
   * {@link GraphEngineCache}, and computation.
   */
  public static RuleAnalysisGraphImpl of(
      GraphComputation<RuleAnalysisKey, RuleAnalysisResult> computation,
      TargetGraph targetGraph,
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      RuleAnalysisCache cache) {
    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(new GraphComputationStage<>(computation, cache)),
            targetGraph.getSize(),
            depsAwareExecutor);
    return new RuleAnalysisGraphImpl(engine);
  }

  @Override
  public RuleAnalysisResult get(RuleAnalysisKey lookupKey) {
    return computationEngine.computeUnchecked(lookupKey);
  }

  @Override
  public ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> getAll(Set<RuleAnalysisKey> lookupKeys) {
    return computationEngine.computeAllUnchecked(lookupKeys);
  }
}
