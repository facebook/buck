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

package com.facebook.buck.core.rules.config.impl;

import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.KnownConfigurationRuleTypes;
import com.facebook.buck.core.rules.type.RuleType;
import com.google.common.collect.ImmutableMap;

public class DefaultKnownConfigurationRuleTypes implements KnownConfigurationRuleTypes {

  private final ImmutableMap<RuleType, ConfigurationRuleDescription<?>>
      configurationRuleDescriptions;

  public DefaultKnownConfigurationRuleTypes(
      ImmutableMap<RuleType, ConfigurationRuleDescription<?>> configurationRuleDescriptions) {
    this.configurationRuleDescriptions = configurationRuleDescriptions;
  }

  @Override
  public ConfigurationRuleDescription<?> getRuleDescription(RuleType configurationRuleType) {
    return configurationRuleDescriptions.get(configurationRuleType);
  }
}
