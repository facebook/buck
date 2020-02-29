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

package com.facebook.buck.parser;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.configsetting.ConfigSettingRule;
import com.google.common.collect.ImmutableList;

/**
 * Checks whether a list of constraints listed in {@code compatible_with} attribute of a target is
 * compatible with a given platform.
 */
class TargetCompatibilityChecker {

  /**
   * @return {@code true} if the given target node argument is compatible with the provided
   *     platform.
   */
  public static boolean targetNodeArgMatchesPlatform(
      ConfigurationRuleRegistry configurationRuleRegistry,
      ConstructorArg targetNodeArg,
      Platform platform,
      DependencyStack dependencyStack,
      BuckConfig buckConfig) {
    if (!(targetNodeArg instanceof BuildRuleArg)) {
      return true;
    }
    BuildRuleArg argWithTargetCompatible = (BuildRuleArg) targetNodeArg;
    if (!argWithTargetCompatible.getCompatibleWith().isEmpty()) {
      return configTargetsMatchPlatform(
          configurationRuleRegistry,
          argWithTargetCompatible.getCompatibleWith(),
          platform,
          dependencyStack,
          buckConfig);
    }

    return true;
  }

  public static boolean configTargetsMatchPlatform(
      ConfigurationRuleRegistry configurationRuleRegistry,
      ImmutableList<UnconfiguredBuildTarget> compatibleConfigTargets,
      Platform platform,
      DependencyStack dependencyStack,
      BuckConfig buckConfig) {
    if (compatibleConfigTargets.isEmpty()) {
      return true;
    }
    ConfigurationRuleResolver configurationRuleResolver =
        configurationRuleRegistry.getConfigurationRuleResolver();
    boolean compatible = false;
    for (UnconfiguredBuildTarget compatibleConfigTarget : compatibleConfigTargets) {
      ConfigSettingRule configSettingRule =
          configurationRuleResolver.getRule(
              ConfigurationBuildTargets.convert(compatibleConfigTarget),
              ConfigSettingRule.class,
              dependencyStack.child(compatibleConfigTarget));

      if (configSettingRule
          .getSelectable()
          .matchesPlatform(
              platform,
              configurationRuleRegistry.getConstraintResolver(),
              dependencyStack.child(compatibleConfigTarget),
              buckConfig)) {
        compatible = true;
        break;
      }
    }
    return compatible;
  }
}
