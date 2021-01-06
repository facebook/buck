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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility to create {@link com.facebook.buck.parser.detector.TargetConfigurationDetector} */
public class TargetConfigurationDetectorFactory {
  private TargetConfigurationDetectorFactory() {}

  /**
   * Detector configuration is a sequence of space-separate detector rules. This pattern patches the
   * rule. The syntax matched is {@code <rule_type>:<rule_arg>-><target_configuration>}.
   */
  private static final Pattern RULE_PATTERN = Pattern.compile("([^,]+)?:(.+)->(.+)");

  private static Pair<TargetConfigurationDetector.Matcher, TargetConfiguration> parseRule(
      String rule,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory,
      CellPathResolver cellPathResolver,
      CellNameResolver cellNameResolver) {
    Matcher matcher = RULE_PATTERN.matcher(rule);
    if (!matcher.matches()) {
      throw new HumanReadableException(
          "detector rule should be in format 'rule_type:rule_arg->target_configuration': '%s'",
          rule);
    }

    String ruleType = matcher.group(1);
    String param = matcher.group(2);
    String target = matcher.group(3);

    TargetConfigurationDetector.Matcher detectorMatcher;
    if (ruleType.equals(TargetConfigurationDetector.SpecMatcher.TYPE)) {
      detectorMatcher =
          new TargetConfigurationDetector.SpecMatcher(
              SimplePackageSpec.parse(param, cellNameResolver));
    } else {
      throw new HumanReadableException(
          "unknown rule type '%s' in detector rule '%s'", ruleType, rule);
    }

    TargetConfigurationFactory targetConfigurationFactory =
        new TargetConfigurationFactory(unconfiguredBuildTargetViewFactory, cellPathResolver);

    TargetConfiguration targetConfiguration = targetConfigurationFactory.create(target);

    return new Pair<>(detectorMatcher, targetConfiguration);
  }

  /** Configure detector using provided {@code .buckconfig} */
  public static TargetConfigurationDetector fromBuckConfig(
      ParserConfig parserConfig,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory,
      CellPathResolver cellPathResolver,
      CellNameResolver cellNameResolver) {
    ImmutableList.Builder<Pair<TargetConfigurationDetector.Matcher, TargetConfiguration>> rules =
        ImmutableList.builder();

    String spec = parserConfig.getTargetPlatformDetectorSpec();
    for (String rule : spec.split(" +")) {
      if (rule.isEmpty()) {
        continue;
      }
      rules.add(
          parseRule(rule, unconfiguredBuildTargetViewFactory, cellPathResolver, cellNameResolver));
    }

    return new TargetConfigurationDetector(rules.build());
  }

  private static final TargetConfigurationDetector EMPTY =
      new TargetConfigurationDetector(ImmutableList.of());

  /** Target configuration that detects to none */
  public static TargetConfigurationDetector empty() {
    return EMPTY;
  }
}
