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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;

/** {@link PlatformResolver} that supports both multiplatforms and regular platforms. */
public class CombinedPlatformResolver implements PlatformResolver {

  private final ConfigurationRuleResolver configurationRuleResolver;
  private final RuleBasedPlatformResolver ruleBasedPlatformResolver;
  private final RuleBasedMultiPlatformResolver ruleBasedMultiPlatformResolver;

  public CombinedPlatformResolver(
      ConfigurationRuleResolver configurationRuleResolver,
      RuleBasedPlatformResolver ruleBasedPlatformResolver,
      RuleBasedMultiPlatformResolver ruleBasedMultiPlatformResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
    this.ruleBasedPlatformResolver = ruleBasedPlatformResolver;
    this.ruleBasedMultiPlatformResolver = ruleBasedMultiPlatformResolver;
  }

  @Override
  public Platform getPlatform(BuildTarget buildTarget) {
    ConfigurationRule configurationRule = configurationRuleResolver.getRule(buildTarget);
    if (configurationRule instanceof PlatformRule) {
      return ruleBasedPlatformResolver.getPlatform(buildTarget);
    }
    if (configurationRule instanceof MultiPlatformRule) {
      return ruleBasedMultiPlatformResolver.getPlatform(buildTarget);
    }
    throw new HumanReadableException(
        "%s is used as a target platform, but not declared using a platform rule", buildTarget);
  }
}
