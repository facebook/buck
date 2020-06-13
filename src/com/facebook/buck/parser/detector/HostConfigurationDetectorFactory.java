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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.tc.factory.TargetConfigurationFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.stream.Collectors;

/** Utility to create {@link TargetConfigurationDetector} */
public class HostConfigurationDetectorFactory {
  private HostConfigurationDetectorFactory() {}

  private static Platform parsePlatform(String platformString) {
    for (Platform platform : Platform.values()) {
      if (platform.getAutoconfName().equals(platformString)) {
        return platform;
      }
    }

    throw new HumanReadableException(
        "Unknown platform: %s, possible variants: %s",
        platformString,
        Arrays.stream(Platform.values())
            .map(Platform::getAutoconfName)
            .collect(Collectors.joining(", ")));
  }

  /** Configure detector using provided {@code .buckconfig} */
  public static HostConfigurationDetector fromBuckConfig(
      ParserConfig parserConfig,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory,
      CellNameResolver cellNameResolver) {

    TargetConfigurationFactory targetConfigurationFactory =
        new TargetConfigurationFactory(unconfiguredBuildTargetViewFactory, cellNameResolver);

    EnumMap<Platform, TargetConfiguration> rules = new EnumMap<>(Platform.class);

    String spec = parserConfig.getHostPlatformDetectorSpec();

    ImmutableList<ConfigurationDetectorFactoryCommon.RawRule> rawRules =
        ConfigurationDetectorFactoryCommon.parseRules(spec, targetConfigurationFactory);

    for (ConfigurationDetectorFactoryCommon.RawRule rawRule : rawRules) {

      if (rawRule.ruleType.equals("host_os")) {
        rules.put(parsePlatform(rawRule.ruleArg), rawRule.targetConfiguration);
      } else {
        throw new HumanReadableException(
            "unknown rule type '%s' in detector rule '%s'", rawRule.ruleType, rawRule);
      }
    }

    return new HostConfigurationDetector(rules);
  }

  private static final HostConfigurationDetector EMPTY =
      new HostConfigurationDetector(new EnumMap<>(Platform.class));

  /** Target configuration that detects to none */
  public static HostConfigurationDetector empty() {
    return EMPTY;
  }
}
