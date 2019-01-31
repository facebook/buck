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

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.graph.transformation.GraphTransformer;
import com.facebook.buck.core.graph.transformation.TransformationEnvironment;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

/**
 * The {@link GraphTransformer} for performing the target graph to provider and action graph
 * transformation.
 *
 * <p>This represents the stage of Buck where the {@link
 * com.facebook.buck.core.model.targetgraph.TargetGraph} is transformed into the {@link
 * com.google.devtools.build.lib.packages.Provider} graph, along with the registered Actions.
 */
public class RuleAnalysisTransformer
    implements GraphTransformer<RuleAnalysisKey, RuleAnalysisResult> {

  private final TargetGraph targetGraph;

  public RuleAnalysisTransformer(TargetGraph targetGraph) {
    this.targetGraph = targetGraph;
  }

  @Override
  public Class<RuleAnalysisKey> getKeyClass() {
    return RuleAnalysisKey.class;
  }

  @Override
  public RuleAnalysisResult transform(RuleAnalysisKey key, TransformationEnvironment env) {
    return transformImpl(targetGraph.get(key.getBuildTarget()), env);
  }

  /**
   * Performs the rule analysis for the rule matching the given {@link BuildTarget}. This will
   * trigger its corresponding {@link
   * com.facebook.buck.core.description.RuleDescription#ruleImpl(RuleAnalysisContext, BuildTarget,
   * Object)}, which will create the rule's exported {@link ProviderInfoCollection} and register
   * it's corresponding Actions.
   *
   * <p>This method is similar in functionality to Bazel's {@code
   * com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction}. {@see <a
   * href="https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/skyframe/ConfiguredTargetFunction.java">ConfiguredTargetFunction</a>}
   *
   * @return an {@link RuleAnalysisResult} containing information about the rule analyzed
   */
  private <T> RuleAnalysisResult transformImpl(
      TargetNode<T> targetNode, TransformationEnvironment env) {
    BaseDescription<T> baseDescription = targetNode.getDescription();
    Verify.verify(baseDescription instanceof RuleDescription);

    RuleDescription<T> ruleDescription = (RuleDescription<T>) baseDescription;

    RuleAnalysisContextImpl ruleAnalysisContext =
        new RuleAnalysisContextImpl(
            ImmutableMap.copyOf(
                Maps.transformValues(
                    env.getDeps(RuleAnalysisKey.class), RuleAnalysisResult::getProviderInfos)));

    ProviderInfoCollection providers =
        ruleDescription.ruleImpl(
            ruleAnalysisContext, targetNode.getBuildTarget(), targetNode.getConstructorArg());

    return ImmutableRuleAnalysisResultImpl.of(
        targetNode.getBuildTarget(),
        providers,
        ImmutableMap.copyOf(ruleAnalysisContext.getRegisteredActionData()));
  }

  @Override
  public ImmutableSet<RuleAnalysisKey> discoverPreliminaryDeps(RuleAnalysisKey key) {
    return ImmutableSet.copyOf(
        Iterables.transform(
            targetGraph.get(key.getBuildTarget()).getParseDeps(),
            ImmutableRuleAnalysisKeyImpl::of));
  }

  @Override
  public ImmutableSet<RuleAnalysisKey> discoverDeps(
      RuleAnalysisKey key, TransformationEnvironment env) {
    return ImmutableSet.of();
  }
}
