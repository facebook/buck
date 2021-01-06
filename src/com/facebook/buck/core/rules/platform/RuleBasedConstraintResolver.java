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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ConstraintResolver} that uses configuration rules obtained from {@link
 * ConfigurationRuleResolver} to create {@link ConstraintSetting} and {@link ConstraintValue}
 * instances.
 *
 * <p>All instances are cached.
 */
public class RuleBasedConstraintResolver implements ConstraintResolver {
  private final ConfigurationRuleResolver configurationRuleResolver;

  public RuleBasedConstraintResolver(ConfigurationRuleResolver configurationRuleResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
  }

  private final ConcurrentHashMap<BuildTarget, ConstraintSettingRule> constraintSettingCache =
      new ConcurrentHashMap<>();

  @Override
  public ConstraintSetting getConstraintSetting(
      BuildTarget buildTarget, DependencyStack dependencyStack) {
    return constraintSettingCache
        .computeIfAbsent(
            buildTarget,
            t -> {
              return configurationRuleResolver.getRule(
                  buildTarget, ConstraintSettingRule.class, dependencyStack);
            })
        .getConstraintSetting();
  }

  private final ConcurrentHashMap<BuildTarget, ConstraintValueRule> constraintValueCache =
      new ConcurrentHashMap<>();

  @Override
  public ConstraintValue getConstraintValue(
      BuildTarget buildTarget, DependencyStack dependencyStack) {
    return constraintValueCache
        .computeIfAbsent(
            buildTarget,
            t -> {
              return configurationRuleResolver.getRule(
                  buildTarget, ConstraintValueRule.class, dependencyStack);
            })
        .getConstraintValue();
  }
}
