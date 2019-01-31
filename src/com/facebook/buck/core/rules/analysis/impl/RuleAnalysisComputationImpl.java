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
package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.graph.transformation.ComputeResult;
import com.facebook.buck.core.graph.transformation.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.GraphTransformationStage;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.analysis.cache.RuleAnalysisCache;
import com.facebook.buck.core.rules.analysis.computation.RuleAnalysisComputation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * Default implementation of {@link RuleAnalysisComputation} driven by a {@link
 * GraphTransformationEngine}
 *
 * <p>TODO(bobyf): once we add stages to {@link GraphTransformationEngine} this implementation and
 * the interface will probably change/go away
 */
public class RuleAnalysisComputationImpl implements RuleAnalysisComputation {

  private final GraphTransformationEngine computationEngine;

  private RuleAnalysisComputationImpl(GraphTransformationEngine computationEngine) {
    this.computationEngine = computationEngine;
  }

  /**
   * creates a new computation with the supplied {@link DepsAwareExecutor} and using the given
   * {@link GraphEngineCache}. All entries present in the cache will be reused, and newly computed
   * values will be offered to the cache.
   */
  public static RuleAnalysisComputationImpl of(
      TargetGraph targetGraph,
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      RuleAnalysisCache cache) {
    RuleAnalysisTransformer transformer = new RuleAnalysisTransformer(targetGraph);
    GraphTransformationEngine engine =
        new DefaultGraphTransformationEngine(
            ImmutableList.of(new GraphTransformationStage<>(transformer, cache)),
            targetGraph.getSize(),
            depsAwareExecutor);
    return new RuleAnalysisComputationImpl(engine);
  }

  @Override
  public RuleAnalysisResult computeUnchecked(RuleAnalysisKey lookupKey) {
    return computationEngine.computeUnchecked(lookupKey);
  }

  @Override
  public ImmutableMap<RuleAnalysisKey, RuleAnalysisResult> computeAllUnchecked(
      Set<RuleAnalysisKey> lookupKeys) {
    return computationEngine.computeAllUnchecked(lookupKeys);
  }
}
