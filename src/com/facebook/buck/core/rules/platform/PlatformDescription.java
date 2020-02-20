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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.platform.ConstraintValueUtil;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleArg;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * A description for {@code platform}.
 *
 * <p>A platform is a set of constraints.
 *
 * <p>For example:
 *
 * <pre>
 *   platform(
 *      name = "platform",
 *      constraint_values = [
 *          ":constraint1",
 *          ":constraint2",
 *      ]
 *   )
 * </pre>
 */
public class PlatformDescription
    implements ConfigurationRuleDescription<PlatformArg, PlatformDescription.PlatformRule> {

  @Override
  public Class<PlatformArg> getConstructorArgType() {
    return PlatformArg.class;
  }

  @Override
  public Class<PlatformRule> getRuleClass() {
    return PlatformRule.class;
  }

  @Override
  public PlatformRule createConfigurationRule(
      ConfigurationRuleResolver configurationRuleResolver,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      PlatformArg arg) {

    ImmutableSet<ConstraintValueRule> constraintValueRules =
        arg.getConstraintValues().stream()
            .map(
                constraintValue ->
                    configurationRuleResolver.getRule(
                        ConfigurationBuildTargets.convert(constraintValue),
                        ConstraintValueRule.class,
                        dependencyStack.child(constraintValue)))
            .collect(ImmutableSet.toImmutableSet());

    ConstraintValueUtil.validateUniqueConstraintSettings(
        "platform",
        buildTarget,
        dependencyStack,
        constraintValueRules.stream()
            .map(ConstraintValueRule::getConstraintValue)
            .collect(ImmutableSet.toImmutableSet()));

    return ImmutablePlatformRule.of(
        buildTarget,
        arg.getName(),
        constraintValueRules,
        ConfigurationBuildTargets.convert(arg.getDeps()));
  }

  @Override
  public ImmutableSet<BuildTarget> getConfigurationDeps(PlatformArg arg) {
    return ImmutableSet.<BuildTarget>builder()
        .addAll(ConfigurationBuildTargets.convert(arg.getConstraintValues()))
        .addAll(ConfigurationBuildTargets.convert(arg.getDeps()))
        .build();
  }

  @RuleArg
  interface AbstractPlatformArg extends ConfigurationRuleArg {
    @Value.NaturalOrder
    @Hint(isConfigurable = false)
    ImmutableSortedSet<UnconfiguredBuildTarget> getConstraintValues();

    @Value.NaturalOrder
    @Hint(isConfigurable = false)
    ImmutableSortedSet<UnconfiguredBuildTarget> getDeps();
  }

  /** {@code platform} rule. */
  @BuckStyleValue
  interface PlatformRule extends ConfigurationRule {
    @Override
    BuildTarget getBuildTarget();

    String getName();

    ImmutableSet<ConstraintValueRule> getConstrainValuesRules();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getDeps();
  }
}
