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
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.select.Selectable;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * {@code Selectable} created by {@link ConfigSettingRule} for integration with {@link
 * com.facebook.buck.core.select.SelectableResolver}.
 */
public class ConfigSettingSelectable implements Selectable {

  private final BuildTarget buildTarget;
  private final ImmutableMap<String, String> values;
  private final ImmutableSet<BuildTarget> constraintValues;

  public ConfigSettingSelectable(
      BuildTarget buildTarget,
      ImmutableMap<String, String> values,
      ImmutableSet<BuildTarget> constraintValues) {
    this.buildTarget = buildTarget;
    this.values = values;
    this.constraintValues = constraintValues;
  }

  @Override
  public boolean matches(SelectableConfigurationContext configurationContext) {
    ConfigSettingSelectableConfigurationContext context =
        (ConfigSettingSelectableConfigurationContext) configurationContext;
    return calculateMatches(
        context.getBuckConfig(),
        context.getConstraintResolver(),
        context.getTargetPlatform(),
        constraintValues,
        values);
  }

  /**
   * A {@link ConfigSettingSelectable} refines another {@link ConfigSettingSelectable} when {@link
   * #values} or {@link #constraintValues} or both are strict supersets of corresponding sets of the
   * other selectable.
   *
   * @return {@code true} for given {@code this} selectable and {@code other} selectable when one of
   *     this conditions is true:
   *     <ul>
   *       <li>{@code this.values} is a strict superset of {@code other.values} and {@code
   *           this.constraintValues} is a strict superset of {@code other.constraintValues}
   *       <li>{@code this.values} is equal to {@code other.values} and {@code
   *           this.constraintValues} is a strict superset of {@code other.constraintValues}
   *       <li>{@code this.values} is a strict superset of {@code other.values} and {@code
   *           this.constraintValues} is equal to {@code other.constraintValues}
   *     </ul>
   */
  @Override
  public boolean refines(Selectable other) {
    Preconditions.checkState(other instanceof ConfigSettingSelectable);
    ConfigSettingSelectable otherSelectable = (ConfigSettingSelectable) other;

    if (values.equals(otherSelectable.values)) {
      return refines(constraintValues, otherSelectable.constraintValues);
    } else if (constraintValues.equals(otherSelectable.constraintValues)) {
      return refines(values.entrySet(), otherSelectable.values.entrySet());
    } else {
      return refines(values.entrySet(), otherSelectable.values.entrySet())
          && refines(constraintValues, otherSelectable.constraintValues);
    }
  }

  private <T> boolean refines(ImmutableSet<T> values, ImmutableSet<T> otherValues) {
    return (values.size() > otherValues.size() && values.containsAll(otherValues));
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  private static boolean calculateMatches(
      BuckConfig buckConfig,
      ConstraintResolver constraintResolver,
      Platform targetPlatform,
      Collection<BuildTarget> constraintValuesTargets,
      ImmutableMap<String, String> values) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (!matches(buckConfig, entry.getKey(), entry.getValue())) {
        return false;
      }
    }
    ImmutableList<ConstraintValue> constraintValues =
        constraintValuesTargets
            .stream()
            .map(constraintResolver::getConstraintValue)
            .collect(ImmutableList.toImmutableList());
    return targetPlatform.matchesAll(constraintValues);
  }

  private static boolean matches(BuckConfig buckConfig, String key, String value) {
    String[] keyParts = key.split("\\.");
    Preconditions.checkArgument(
        keyParts.length == 2,
        String.format("Config option should be in format 'section.option', but given: %s", key));

    Optional<String> currentValue = buckConfig.getValue(keyParts[0], keyParts[1]);
    return currentValue.map(curValue -> curValue.equals(value)).orElse(false);
  }

  @Override
  public String toString() {
    return buildTarget.toString();
  }
}
