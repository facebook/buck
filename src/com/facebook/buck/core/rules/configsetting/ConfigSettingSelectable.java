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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.select.Selectable;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

/**
 * {@code Selectable} created by {@link ConfigSettingRule} for integration with {@link
 * com.facebook.buck.core.select.SelectableResolver}.
 */
public class ConfigSettingSelectable implements Selectable {

  private final BuildTarget buildTarget;
  private final ImmutableMap<String, String> values;

  public ConfigSettingSelectable(BuildTarget buildTarget, ImmutableMap<String, String> values) {
    this.buildTarget = buildTarget;
    this.values = values;
  }

  @Override
  public boolean matches(SelectableConfigurationContext configurationContext) {
    ConfigSettingSelectableConfigurationContext context =
        (ConfigSettingSelectableConfigurationContext) configurationContext;
    return calculateMatches(context.getBuckConfig(), values);
  }

  @Override
  public boolean refines(Selectable other) {
    Preconditions.checkState(other instanceof ConfigSettingSelectable);
    Set<Entry<String, String>> settings = ImmutableSet.copyOf(values.entrySet());
    Set<Entry<String, String>> otherSettings =
        ImmutableSet.copyOf(((ConfigSettingSelectable) other).values.entrySet());

    if (!settings.containsAll(otherSettings)) {
      return false;
    }

    if (!(settings.size() > otherSettings.size())) {
      return false;
    }

    return true;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  private static boolean calculateMatches(
      BuckConfig buckConfig, ImmutableMap<String, String> values) {
    if (values.isEmpty()) {
      return false;
    }
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (!matches(buckConfig, entry.getKey(), entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static boolean matches(BuckConfig buckConfig, String key, String value) {
    String[] keyParts = key.split("\\.");
    Preconditions.checkArgument(
        keyParts.length == 2,
        String.format("Config option should be in format 'section.option', but given: %s", key));

    Optional<String> currentValue = buckConfig.getValue(keyParts[0], keyParts[1]);
    if (!currentValue.isPresent()) {
      return false;
    }
    return currentValue.get().equals(value);
  }
}
