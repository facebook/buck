/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * {@link ConstraintResolver} that uses configuration rules (obtained from {@link
 * ConfigurationRuleResolver}) to create instances {@link ConstraintSetting} and {@link
 * ConstraintValue}.
 *
 * <p>All instances are cached.
 */
public class RuleBasedConstraintResolver implements ConstraintResolver {
  private final ConfigurationRuleResolver configurationRuleResolver;

  private final LoadingCache<BuildTarget, ConstraintSetting> constraintSettingCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<BuildTarget, ConstraintSetting>() {
                @Override
                public ConstraintSetting load(BuildTarget buildTarget) {
                  ConfigurationRule configurationRule =
                      configurationRuleResolver.getRule(buildTarget);
                  Preconditions.checkState(
                      configurationRule instanceof ConstraintSettingRule,
                      "%s is used as constraint_setting, but has wrong type",
                      buildTarget);
                  return ConstraintSetting.of(configurationRule.getBuildTarget());
                }
              });

  private final LoadingCache<BuildTarget, ConstraintValue> constraintValueCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<BuildTarget, ConstraintValue>() {
                @Override
                public ConstraintValue load(BuildTarget buildTarget) {
                  ConfigurationRule configurationRule =
                      configurationRuleResolver.getRule(buildTarget);
                  Preconditions.checkState(
                      configurationRule instanceof ConstraintValueRule,
                      "%s is used as constraint_value, but has wrong type",
                      buildTarget);

                  ConstraintValueRule constraintValueRule = (ConstraintValueRule) configurationRule;

                  return ConstraintValue.of(
                      buildTarget,
                      getConstraintSetting(constraintValueRule.getConstraintSetting()));
                }
              });

  public RuleBasedConstraintResolver(ConfigurationRuleResolver configurationRuleResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
  }

  @Override
  public ConstraintSetting getConstraintSetting(BuildTarget buildTarget) {
    try {
      return constraintSettingCache.getUnchecked(buildTarget);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e.getCause());
    }
  }

  @Override
  public ConstraintValue getConstraintValue(BuildTarget buildTarget) {
    try {
      return constraintValueCache.getUnchecked(buildTarget);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e.getCause());
    }
  }
}
