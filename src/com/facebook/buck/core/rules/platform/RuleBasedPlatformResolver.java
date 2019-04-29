/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class RuleBasedPlatformResolver implements PlatformResolver {

  private final ConfigurationRuleResolver configurationRuleResolver;
  private final ConstraintResolver constraintResolver;

  public RuleBasedPlatformResolver(
      ConfigurationRuleResolver configurationRuleResolver, ConstraintResolver constraintResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
    this.constraintResolver = constraintResolver;
  }

  @Override
  public Platform getPlatform(UnconfiguredBuildTargetView buildTarget) {
    GraphTraversable<UnconfiguredBuildTargetView> traversable =
        target -> {
          PlatformRule platformRule = getPlatformRule(target);
          return platformRule.getDeps().iterator();
        };

    AcyclicDepthFirstPostOrderTraversal<UnconfiguredBuildTargetView> platformTraversal =
        new AcyclicDepthFirstPostOrderTraversal<>(traversable);

    ImmutableSet<UnconfiguredBuildTargetView> platformTargets;
    try {
      platformTargets =
          ImmutableSet.copyOf(platformTraversal.traverse(ImmutableList.of(buildTarget)));
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    }

    ImmutableSet<ConstraintValue> constraintValues =
        platformTargets.stream()
            .map(this::getPlatformRule)
            .flatMap(rule -> rule.getConstrainValues().stream())
            .map(constraintResolver::getConstraintValue)
            .collect(ImmutableSet.toImmutableSet());

    return new ConstraintBasedPlatform(buildTarget.getFullyQualifiedName(), constraintValues);
  }

  private PlatformRule getPlatformRule(UnconfiguredBuildTargetView buildTarget) {
    ConfigurationRule configurationRule = configurationRuleResolver.getRule(buildTarget);
    if (!(configurationRule instanceof PlatformRule)) {
      throw new HumanReadableException(
          "%s is used as a target platform, but not declared using `platform` rule",
          buildTarget.getFullyQualifiedName());
    }
    return (PlatformRule) configurationRule;
  }
}
