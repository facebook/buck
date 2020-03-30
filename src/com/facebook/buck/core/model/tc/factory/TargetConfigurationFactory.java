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

package com.facebook.buck.core.model.tc.factory;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import java.util.Optional;

/** Parse a string into {@link TargetConfiguration}. */
public class TargetConfigurationFactory {

  private static final String UNCONFIGURED_TARGET_CONFIGURATION_NAME =
      "builtin//platform:unconfigured";

  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory;
  private final CellPathResolver cellPathResolver;

  public TargetConfigurationFactory(
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory,
      CellPathResolver cellPathResolver) {
    this.unconfiguredBuildTargetViewFactory = unconfiguredBuildTargetViewFactory;
    this.cellPathResolver = cellPathResolver;
  }

  private Optional<TargetConfiguration> tryNonBuildTarget(String targetConfiguration) {
    if (targetConfiguration.equals(UNCONFIGURED_TARGET_CONFIGURATION_NAME)) {
      return Optional.of(UnconfiguredTargetConfiguration.INSTANCE);
    }
    return Optional.empty();
  }

  /** Create a target configuration by absolute buck target name */
  public TargetConfiguration create(String targetConfiguration) {
    Optional<TargetConfiguration> builtin = tryNonBuildTarget(targetConfiguration);
    if (builtin.isPresent()) {
      return builtin.get();
    }

    UnconfiguredBuildTarget buildTarget =
        unconfiguredBuildTargetViewFactory.create(
            targetConfiguration, cellPathResolver.getCellNameResolver());
    return RuleBasedTargetConfiguration.of(ConfigurationBuildTargets.convert(buildTarget));
  }
}
