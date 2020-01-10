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

package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import java.util.Map;
import java.util.Objects;

// This ugliness is necessary as we don't have mocks in Buck unit tests.
public class FakeActionGraphBuilder extends TestActionGraphBuilder {
  private final Map<BuildTarget, BuildRule> ruleMap;

  public FakeActionGraphBuilder(Map<BuildTarget, BuildRule> ruleMap) {
    this(TargetGraph.EMPTY, ruleMap);
  }

  public FakeActionGraphBuilder(TargetGraph targetGraph, Map<BuildTarget, BuildRule> ruleMap) {
    super(
        targetGraph,
        new DefaultTargetNodeToBuildRuleTransformer(),
        new TestCellBuilder().build().getCellProvider());
    this.ruleMap = ruleMap;
  }

  @Override
  public BuildRule getRule(BuildTarget target) {
    return Objects.requireNonNull(ruleMap.get(target), "No rule for target: " + target);
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    return Objects.requireNonNull(ruleMap.get(target), "No rule for target: " + target);
  }
}
