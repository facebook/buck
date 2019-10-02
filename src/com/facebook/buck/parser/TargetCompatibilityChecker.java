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
package com.facebook.buck.parser;

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.arg.HasTargetCompatibleWith;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.configsetting.ConfigSettingRule;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Checks whether a list of constraints listed in {@code target_compatible_with} attribute of a
 * target is compatible with a given platform.
 */
class TargetCompatibilityChecker {

  /**
   * @return {@code true} if the given target node argument is compatible with the provided
   *     platform.
   */
  public static boolean targetNodeArgMatchesPlatform(
      ConfigurationRuleRegistry configurationRuleRegistry,
      ConstructorArg targetNodeArg,
      Platform platform) {
    if (!(targetNodeArg instanceof HasTargetCompatibleWith)) {
      return true;
    }
    HasTargetCompatibleWith argWithTargetCompatible = (HasTargetCompatibleWith) targetNodeArg;
    ConstraintResolver constraintResolver = configurationRuleRegistry.getConstraintResolver();

    List<ConstraintValue> targetCompatibleWithConstraints =
        argWithTargetCompatible.getTargetCompatibleWith().stream()
            .map(BuildTarget::getUnconfiguredBuildTargetView)
            .map(ConfigurationBuildTargets::convert)
            .map(constraintResolver::getConstraintValue)
            .collect(Collectors.toList());
    if (!targetCompatibleWithConstraints.isEmpty()) {
      // Empty `target_compatible_with` means target is compatible.
      if (!platform.matchesAll(targetCompatibleWithConstraints)) {
        return false;
      }
    }

    if (!argWithTargetCompatible.getCompatibleWith().isEmpty()) {
      ConfigurationRuleResolver configurationRuleResolver =
          configurationRuleRegistry.getConfigurationRuleResolver();
      boolean compatible = false;
      for (UnconfiguredBuildTargetView compatibleConfigTarget :
          argWithTargetCompatible.getCompatibleWith()) {
        ConfigSettingRule configSettingRule =
            (ConfigSettingRule)
                configurationRuleResolver.getRule(
                    ConfigurationBuildTargets.convert(compatibleConfigTarget));

        if (configSettingRule.getSelectable().matchesPlatform(platform, constraintResolver)) {
          compatible = true;
          break;
        }
      }

      if (!compatible) {
        return false;
      }
    }

    return true;
  }
}
