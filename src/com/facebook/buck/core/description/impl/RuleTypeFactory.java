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

package com.facebook.buck.core.description.impl;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.CaseFormat;

/** Utility to create {@link RuleType} */
public class RuleTypeFactory {

  /** Create a rule type from a rule implementation class */
  public static RuleType create(Class<? extends BaseDescription<?>> cls) {
    String result = cls.getSimpleName();
    result = MoreStrings.stripPrefix(result, "Abstract").orElse(result);
    result = MoreStrings.stripSuffix(result, "Description").orElse(result);

    boolean isBuildRule =
        DescriptionWithTargetGraph.class.isAssignableFrom(cls)
            || RuleDescription.class.isAssignableFrom(cls);
    boolean isConfigurationRule = ConfigurationRuleDescription.class.isAssignableFrom(cls);

    RuleType.Kind ruleKind;
    if (isBuildRule && !isConfigurationRule) {
      ruleKind = RuleType.Kind.BUILD;
    } else if (isConfigurationRule && !isBuildRule) {
      ruleKind = RuleType.Kind.CONFIGURATION;
    } else if (isBuildRule) {
      throw new IllegalStateException(
          "rule cannot be both build and configuration: " + cls.getName());
    } else {
      throw new IllegalStateException("cannot determine rule kind: " + cls.getName());
    }
    return RuleType.of(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, result), ruleKind);
  }
}
