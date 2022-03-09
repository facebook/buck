/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;

/**
 * Utility to create {@link com.facebook.buck.parser.detector.TargetConfigurationDetector} from a
 * spec in buckconfig.
 */
public class TargetConfigurationDetectorFactory {
  private TargetConfigurationDetectorFactory() {}

  /** Configure detector using provided {@code .buckconfig} */
  public static TargetConfigurationDetector fromBuckConfig(
      ParserConfig parserConfig,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory,
      CellNameResolver cellNameResolver) {
    TargetConfigurationFactory targetConfigurationFactory =
        new TargetConfigurationFactory(unconfiguredBuildTargetViewFactory, cellNameResolver);

    ImmutableList.Builder<Pair<TargetConfigurationDetector.Matcher, TargetConfiguration>> rules =
        ImmutableList.builder();

    String spec = parserConfig.getTargetPlatformDetectorSpec();
    for (ConfigurationDetectorFactoryCommon.RawRule rawRule :
        ConfigurationDetectorFactoryCommon.parseRules(spec, targetConfigurationFactory)) {

      TargetConfigurationDetector.Matcher detectorMatcher;
      if (rawRule.ruleType.equals(TargetConfigurationDetector.SpecMatcher.TYPE)) {
        detectorMatcher =
            new TargetConfigurationDetector.SpecMatcher(
                SimplePackageSpec.parse(rawRule.ruleArg, cellNameResolver));
      } else {
        throw new HumanReadableException(
            "unknown rule type '%s' in detector rule '%s'", rawRule.ruleType, rawRule);
      }

      rules.add(new Pair<>(detectorMatcher, rawRule.targetConfiguration));
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
