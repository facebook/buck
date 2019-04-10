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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.DefaultTargetConfiguration;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

/**
 * An implementation of {@link TargetPlatformResolver} that creates {@link Platform} from {@link
 * PlatformRule} for {@link com.facebook.buck.core.model.impl.DefaultTargetConfiguration}.
 *
 * <p>Note that the clients of this class need to make sure the queries are made with the correct
 * target configuration type.
 */
public class RuleBasedTargetPlatformResolver implements TargetPlatformResolver {

  private final ConfigurationRuleResolver configurationRuleResolver;
  private final ConstraintResolver constraintResolver;

  public RuleBasedTargetPlatformResolver(
      ConfigurationRuleResolver configurationRuleResolver, ConstraintResolver constraintResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
    this.constraintResolver = constraintResolver;
  }

  @Override
  public Platform getTargetPlatform(TargetConfiguration targetConfiguration) {
    Preconditions.checkState(
        targetConfiguration instanceof DefaultTargetConfiguration,
        "Wrong target configuration type: " + targetConfiguration);

    UnconfiguredBuildTargetView buildTarget =
        ((DefaultTargetConfiguration) targetConfiguration).getTargetPlatform();

    ConfigurationRule configurationRule = configurationRuleResolver.getRule(buildTarget);

    if (!(configurationRule instanceof PlatformRule)) {
      throw new HumanReadableException(
          "%s is used as a target platform, but not declared using `platform` rule",
          buildTarget.getFullyQualifiedName());
    }

    PlatformRule platformRule = (PlatformRule) configurationRule;

    ImmutableSet<ConstraintValue> constraintValues =
        platformRule.getConstrainValues().stream()
            .map(constraintResolver::getConstraintValue)
            .collect(ImmutableSet.toImmutableSet());

    return new ConstraintBasedPlatform(buildTarget.getFullyQualifiedName(), constraintValues);
  }
}
