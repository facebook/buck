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

package com.facebook.buck.parser.detector;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for host and target configuration detectors. */
class ConfigurationDetectorFactoryCommon {
  /** Host or target rule data. */
  static class RawRule {
    final String ruleType;
    final String ruleArg;
    final TargetConfiguration targetConfiguration;

    public RawRule(String ruleType, String ruleArg, TargetConfiguration targetConfiguration) {
      this.ruleType = ruleType;
      this.ruleArg = ruleArg;
      this.targetConfiguration = targetConfiguration;
    }

    @Override
    public String toString() {
      return ruleType + ":" + ruleArg + "->" + targetConfiguration;
    }
  }

  /**
   * Detector configuration is a sequence of space-separate detector rules. This pattern patches the
   * rule. The syntax matched is {@code <rule_type>:<rule_arg>-><target_configuration>}.
   */
  private static final Pattern RULE_PATTERN = Pattern.compile("([^,]+)?:(.+)->(.+)");

  private static RawRule parseRule(
      String rule, TargetConfigurationFactory targetConfigurationFactory) {
    Matcher matcher = RULE_PATTERN.matcher(rule);
    if (!matcher.matches()) {
      throw new HumanReadableException(
          "detector rule should be in format 'rule_type:rule_arg->target_configuration': '%s'",
          rule);
    }

    String ruleType = matcher.group(1);
    String param = matcher.group(2);
    String target = matcher.group(3);

    TargetConfiguration targetConfiguration = targetConfigurationFactory.create(target);

    return new RawRule(ruleType, param, targetConfiguration);
  }

  static ImmutableList<RawRule> parseRules(
      String spec, TargetConfigurationFactory targetConfigurationFactory) {

    ImmutableList.Builder<RawRule> rules = ImmutableList.builder();
    for (String rule : spec.split(" +")) {
      if (rule.isEmpty()) {
        continue;
      }
      rules.add(parseRule(rule, targetConfigurationFactory));
    }
    return rules.build();
  }
}
