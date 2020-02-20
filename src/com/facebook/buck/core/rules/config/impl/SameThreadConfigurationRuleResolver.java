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

package com.facebook.buck.core.rules.config.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleArg;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Provides a mechanism for mapping between a {@link
 * com.facebook.buck.core.model.UnconfiguredBuildTarget} and the {@link ConfigurationRule} it
 * represents.
 *
 * <p>This resolver performs all computations on the same thread {@link
 * ConfigurationRuleResolver#getRule} was called from.
 */
public class SameThreadConfigurationRuleResolver implements ConfigurationRuleResolver {

  private final BiFunction<BuildTarget, DependencyStack, TargetNode<?>> targetNodeSupplier;
  private final ConcurrentHashMap<BuildTarget, ConfigurationRule> configurationRuleIndex;

  public SameThreadConfigurationRuleResolver(
      BiFunction<BuildTarget, DependencyStack, TargetNode<?>> targetNodeSupplier) {
    this.targetNodeSupplier = targetNodeSupplier;
    this.configurationRuleIndex = new ConcurrentHashMap<>();
  }

  private ConfigurationRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, ConfigurationRule> mappingFunction) {
    @Nullable ConfigurationRule configurationRule = configurationRuleIndex.get(target);
    if (configurationRule != null) {
      return configurationRule;
    }
    configurationRule = mappingFunction.apply(target);
    ConfigurationRule previousRule = configurationRuleIndex.putIfAbsent(target, configurationRule);
    return previousRule == null ? configurationRule : previousRule;
  }

  @Override
  public <R extends ConfigurationRule> R getRule(
      BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
    ConfigurationRule configurationRule =
        computeIfAbsent(buildTarget, t -> createConfigurationRule(t, ruleClass, dependencyStack));
    try {
      return ruleClass.cast(configurationRule);
    } catch (ClassCastException e) {
      throw wrongRuleClassException(
          buildTarget, dependencyStack, ruleClass, configurationRule.getClass());
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends ConfigurationRuleArg, R extends ConfigurationRule>
      ConfigurationRule createConfigurationRule(
          BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
    Preconditions.checkArgument(
        buildTarget.getTargetConfiguration() == ConfigurationForConfigurationTargets.INSTANCE);

    TargetNode<T> targetNode =
        (TargetNode<T>) targetNodeSupplier.apply(buildTarget, dependencyStack);
    ConfigurationRuleDescription<T, ?> configurationRuleDescription =
        (ConfigurationRuleDescription<T, ?>) targetNode.getDescription();

    if (!ruleClass.isAssignableFrom(configurationRuleDescription.getRuleClass())) {
      throw wrongRuleClassException(
          buildTarget, dependencyStack, ruleClass, configurationRuleDescription.getRuleClass());
    }

    ConfigurationRule configurationRule =
        configurationRuleDescription.createConfigurationRule(
            this, buildTarget, dependencyStack, targetNode.getConstructorArg());
    Preconditions.checkState(
        configurationRule.getBuildTarget().equals(buildTarget),
        "Configuration rule description returned rule for '%s' instead of '%s'.",
        configurationRule.getBuildTarget(),
        buildTarget);
    return configurationRule;
  }

  private <R extends ConfigurationRule> HumanReadableException wrongRuleClassException(
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      Class<R> requestedRuleClass,
      Class<? extends ConfigurationRule> actualRuleClass) {
    return new HumanReadableException(
        dependencyStack,
        "requested rule %s of type %s, but it was %s",
        buildTarget,
        ruleNameFromRuleClass(requestedRuleClass),
        ruleNameFromRuleClass(actualRuleClass));
  }

  private static String ruleNameFromRuleClass(Class<? extends ConfigurationRule> ruleClass) {
    // TODO(nga): rule name is determined by descriptor class name, not rule class name
    String result = ruleClass.getSimpleName();
    result = MoreStrings.stripPrefix(result, "Abstract").orElse(result);
    result = MoreStrings.stripSuffix(result, "Rule").orElse(result);
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, result);
  }
}
