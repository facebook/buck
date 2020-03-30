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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversalWithPayloadAndDependencyStack;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.GraphTraversableWithPayloadAndDependencyStack;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;

public class RuleBasedPlatformResolver implements PlatformResolver {

  private final ConfigurationRuleResolver configurationRuleResolver;

  public RuleBasedPlatformResolver(ConfigurationRuleResolver configurationRuleResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
  }

  @Override
  public NamedPlatform getPlatform(BuildTarget buildTarget, DependencyStack dependencyStack) {

    GraphTraversableWithPayloadAndDependencyStack<BuildTarget, PlatformDescription.PlatformRule>
        traversable =
            (target, dependencyStack1) -> {
              PlatformDescription.PlatformRule platformRule =
                  getPlatformRule(target, dependencyStack1);
              return new Pair<>(platformRule, platformRule.getDeps().iterator());
            };

    AcyclicDepthFirstPostOrderTraversalWithPayloadAndDependencyStack<
            BuildTarget, PlatformDescription.PlatformRule>
        platformTraversal =
            new AcyclicDepthFirstPostOrderTraversalWithPayloadAndDependencyStack<>(
                traversable, DependencyStack::child);

    LinkedHashMap<BuildTarget, Pair<PlatformDescription.PlatformRule, DependencyStack>>
        platformTargets;
    try {
      platformTargets = platformTraversal.traverse(ImmutableList.of(buildTarget));
    } catch (CycleException e) {
      throw new HumanReadableException(dependencyStack, e.getMessage());
    }

    ImmutableSet<ConstraintValue> constraintValues =
        platformTargets.values().stream()
            .flatMap(t -> t.getFirst().getConstrainValuesRules().stream())
            .map(ConstraintValueRule::getConstraintValue)
            .collect(ImmutableSet.toImmutableSet());

    return new ConstraintBasedPlatform(buildTarget, constraintValues);
  }

  private PlatformDescription.PlatformRule getPlatformRule(
      BuildTarget buildTarget, DependencyStack dependencyStack) {
    ConfigurationRule configurationRule =
        configurationRuleResolver.getRule(buildTarget, ConfigurationRule.class, dependencyStack);
    if (!(configurationRule instanceof PlatformDescription.PlatformRule)) {
      throw new HumanReadableException(
          dependencyStack,
          "%s is used as a target platform, but not declared using `platform` rule",
          buildTarget.getFullyQualifiedName());
    }
    return (PlatformDescription.PlatformRule) configurationRule;
  }
}
