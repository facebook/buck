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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.base.Preconditions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Provides a mechanism for mapping between a {@link UnconfiguredBuildTargetView} and the {@link
 * ConfigurationRule} it represents.
 *
 * <p>This resolver performs all computations on the same thread {@link #getRule} was called from.
 */
public class SameThreadConfigurationRuleResolver implements ConfigurationRuleResolver {

  private final Function<UnconfiguredBuildTargetView, Cell> cellProvider;
  private final BiFunction<Cell, UnconfiguredBuildTargetView, TargetNode<?>> targetNodeSupplier;
  private final ConcurrentHashMap<UnconfiguredBuildTargetView, ConfigurationRule>
      configurationRuleIndex;

  public SameThreadConfigurationRuleResolver(
      Function<UnconfiguredBuildTargetView, Cell> cellProvider,
      BiFunction<Cell, UnconfiguredBuildTargetView, TargetNode<?>> targetNodeSupplier) {
    this.cellProvider = cellProvider;
    this.targetNodeSupplier = targetNodeSupplier;
    this.configurationRuleIndex = new ConcurrentHashMap<>();
  }

  private ConfigurationRule computeIfAbsent(
      UnconfiguredBuildTargetView target,
      Function<UnconfiguredBuildTargetView, ConfigurationRule> mappingFunction) {
    @Nullable ConfigurationRule configurationRule = configurationRuleIndex.get(target);
    if (configurationRule != null) {
      return configurationRule;
    }
    configurationRule = mappingFunction.apply(target);
    ConfigurationRule previousRule = configurationRuleIndex.putIfAbsent(target, configurationRule);
    return previousRule == null ? configurationRule : previousRule;
  }

  @Override
  public ConfigurationRule getRule(UnconfiguredBuildTargetView buildTarget) {
    return computeIfAbsent(buildTarget, this::createConfigurationRule);
  }

  private <T> ConfigurationRule createConfigurationRule(UnconfiguredBuildTargetView buildTarget) {
    Cell cell = cellProvider.apply(buildTarget);
    @SuppressWarnings("unchecked")
    TargetNode<T> targetNode = (TargetNode<T>) targetNodeSupplier.apply(cell, buildTarget);
    if (!(targetNode.getDescription() instanceof ConfigurationRuleDescription)) {
      throw new HumanReadableException(
          "%s was used to resolve configurable attribute but it is not a configuration rule",
          buildTarget);
    }
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
