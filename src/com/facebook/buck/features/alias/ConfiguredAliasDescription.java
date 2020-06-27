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

package com.facebook.buck.features.alias;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.immutables.RuleArg;

/** {@code Description} class which represents the {@code configred_alias} rule */
public class ConfiguredAliasDescription
    extends AbstractAliasDescription<ConfiguredAliasDescriptionArg> {

  @Override
  public Class<ConfiguredAliasDescriptionArg> getConstructorArgType() {
    return ConfiguredAliasDescriptionArg.class;
  }

  @Override
  public BuildTarget resolveActualBuildTarget(ConfiguredAliasDescriptionArg args) {
    TargetConfiguration configuration =
        RuleBasedTargetConfiguration.of(ConfigurationBuildTargets.convert(args.getPlatform()));
    return args.getActual().configure(configuration);
  }

  /** {@code RuleArg} for the {@code configured_alias} rule */
  @RuleArg
  interface AbstractConfiguredAliasDescriptionArg extends BuildRuleArg {
    UnconfiguredBuildTarget getActual();

    @Hint(isDep = false)
    UnflavoredBuildTarget getPlatform();
  }
}
