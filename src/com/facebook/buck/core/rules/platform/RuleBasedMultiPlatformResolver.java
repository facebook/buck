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
import com.facebook.buck.core.model.platform.impl.MultiPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.collect.ImmutableList;

/** {@link PlatformResolver} that supports multiplatforms. */
public class RuleBasedMultiPlatformResolver implements PlatformResolver {

  private final ConfigurationRuleResolver configurationRuleResolver;
  private final RuleBasedPlatformResolver ruleBasedPlatformResolver;

  public RuleBasedMultiPlatformResolver(
      ConfigurationRuleResolver configurationRuleResolver,
      RuleBasedPlatformResolver ruleBasedPlatformResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
    this.ruleBasedPlatformResolver = ruleBasedPlatformResolver;
  }

  @Override
  public Platform getPlatform(BuildTarget buildTarget) {
    MultiPlatformRule multiPlatformRule = getMultiPlatformRule(buildTarget);
    Platform basePlatform =
        ruleBasedPlatformResolver.getPlatform(multiPlatformRule.getBasePlatform());
    ImmutableList<Platform> nestedPlatform =
        multiPlatformRule.getNestedPlatforms().stream()
            .map(ruleBasedPlatformResolver::getPlatform)
            .collect(ImmutableList.toImmutableList());

    return new MultiPlatform(buildTarget, basePlatform, nestedPlatform);
  }

  private MultiPlatformRule getMultiPlatformRule(BuildTarget buildTarget) {
    ConfigurationRule configurationRule = configurationRuleResolver.getRule(buildTarget);
    if (!(configurationRule instanceof MultiPlatformRule)) {
      throw new HumanReadableException(
          "%s is used as a multiplatform, but not declared using an appropriate rule",
          buildTarget.getFullyQualifiedName());
    }
    return (MultiPlatformRule) configurationRule;
  }
}
