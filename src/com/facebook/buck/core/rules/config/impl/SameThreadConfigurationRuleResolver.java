/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.rules.config.impl;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.base.Preconditions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Provides a mechanism for mapping between a {@link BuildTarget} and the {@link ConfigurationRule}
 * it represents.
 *
 * <p>This resolver performs all computations on the same thread {@link #getRule} was called from.
 */
public class SameThreadConfigurationRuleResolver implements ConfigurationRuleResolver {

  private final Function<BuildTarget, Cell> cellProvider;
  private final BiFunction<Cell, BuildTarget, TargetNode<?>> targetNodeSupplier;
  private final ConcurrentHashMap<BuildTarget, ConfigurationRule> configurationRuleIndex;

  public SameThreadConfigurationRuleResolver(
      Function<BuildTarget, Cell> cellProvider,
      BiFunction<Cell, BuildTarget, TargetNode<?>> targetNodeSupplier) {
    this.cellProvider = cellProvider;
    this.targetNodeSupplier = targetNodeSupplier;
    this.configurationRuleIndex = new ConcurrentHashMap<>();
  }

  private ConfigurationRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, ConfigurationRule> mappingFunction) {
    ConfigurationRule configurationRule = configurationRuleIndex.get(target);
    if (configurationRule != null) {
      return configurationRule;
    }
    configurationRule = mappingFunction.apply(target);
    configurationRuleIndex.put(target, configurationRule);
    return configurationRule;
  }

  @Override
  public synchronized ConfigurationRule getRule(BuildTarget buildTarget) {
    return computeIfAbsent(buildTarget, ignored -> createConfigurationRule(buildTarget));
  }

  private <T> ConfigurationRule createConfigurationRule(BuildTarget buildTarget) {
    Cell cell = cellProvider.apply(buildTarget);
    @SuppressWarnings("unchecked")
    TargetNode<T> targetNode = (TargetNode<T>) targetNodeSupplier.apply(cell, buildTarget);
    Preconditions.checkState(
        targetNode.getDescription() instanceof ConfigurationRuleDescription,
        "Invalid type of target node description: %s",
        targetNode.getDescription().getClass());
    ConfigurationRuleDescription<T> configurationRuleDescription =
        (ConfigurationRuleDescription<T>) targetNode.getDescription();
    ConfigurationRule configurationRule =
        configurationRuleDescription.createConfigurationRule(
            this, cell, buildTarget, targetNode.getConstructorArg());
    Preconditions.checkState(
        configurationRule.getBuildTarget().equals(buildTarget),
        "Configuration rule description returned rule for '%s' instead of '%s'.",
        configurationRule.getBuildTarget(),
        buildTarget);
    return configurationRule;
  }
}
