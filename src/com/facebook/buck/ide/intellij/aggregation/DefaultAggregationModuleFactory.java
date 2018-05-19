/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.aggregation;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.ide.intellij.SupportedTargetTypeRegistry;
import com.facebook.buck.ide.intellij.model.IjModuleRule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

public class DefaultAggregationModuleFactory implements AggregationModuleFactory {

  private final SupportedTargetTypeRegistry typeRegistry;

  public DefaultAggregationModuleFactory(SupportedTargetTypeRegistry typeRegistry) {
    this.typeRegistry = typeRegistry;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public AggregationModule createAggregationModule(
      Path moduleBasePath, ImmutableSet<TargetNode<?, ?>> targetNodes) {
    AggregationContext context = new AggregationContext();

    for (TargetNode<?, ?> targetNode : targetNodes) {
      Class<?> nodeType = targetNode.getDescription().getClass();
      IjModuleRule<?> rule =
          Preconditions.checkNotNull(typeRegistry.getModuleRuleByTargetNodeType(nodeType));
      rule.applyDuringAggregation(context, (TargetNode) targetNode);
    }

    context.finishModuleCreation();

    return AggregationModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .setAggregationTag(context.getAggregationTag())
        .setModuleType(context.getModuleType())
        .build();
  }
}
