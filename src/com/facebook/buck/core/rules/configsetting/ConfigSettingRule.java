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

package com.facebook.buck.core.rules.configsetting;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.select.ProvidesSelectable;
import com.facebook.buck.core.select.Selectable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** {@code config_setting} rule. */
public class ConfigSettingRule implements ConfigurationRule, ProvidesSelectable {

  private final BuildTarget buildTarget;

  private final ConfigSettingSelectable configSettingSelectable;

  public ConfigSettingRule(
      BuildTarget buildTarget,
      ImmutableMap<String, String> values,
      ImmutableSet<BuildTarget> constraintValues) {
    this.buildTarget = buildTarget;
    configSettingSelectable = new ConfigSettingSelectable(buildTarget, values, constraintValues);
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public Selectable getSelectable() {
    return configSettingSelectable;
  }
}
