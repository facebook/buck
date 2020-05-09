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

package com.facebook.buck.core.rules.configsetting;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.select.Selectable;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * {@code Selectable} created by {@link ConfigSettingRule} for integration with {@link
 * com.facebook.buck.core.select.SelectableResolver}.
 */
@BuckStyleValue
public abstract class ConfigSettingSelectable implements Selectable {

  public abstract ImmutableMap<BuckConfigKey, String> getValues();

  public abstract ImmutableSet<ConstraintValue> getConstraintValues();

  @Override
  public boolean matchesPlatform(
      Platform platform, BuckConfig buckConfig, DependencyStack dependencyStack) {
    return calculateMatches(
        buckConfig, platform, dependencyStack, getConstraintValues(), getValues());
  }

  /**
   * A {@link ConfigSettingSelectable} refines another {@link ConfigSettingSelectable} when {@link
   * #getValues()} or {@link #getConstraintValues()} or both are strict supersets of corresponding
   * sets of the other selectable.
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

    if (getValues().equals(otherSelectable.getValues())) {
      return refines(getConstraintValues(), otherSelectable.getConstraintValues());
    } else if (getConstraintValues().equals(otherSelectable.getConstraintValues())) {
      return refines(getValues().entrySet(), otherSelectable.getValues().entrySet());
    } else {
      return refines(getValues().entrySet(), otherSelectable.getValues().entrySet())
          && refines(getConstraintValues(), otherSelectable.getConstraintValues());
    }
  }

  private <T> boolean refines(ImmutableSet<T> values, ImmutableSet<T> otherValues) {
    return (values.size() > otherValues.size() && values.containsAll(otherValues));
  }

  private static boolean calculateMatches(
      BuckConfig buckConfig,
      Platform targetPlatform,
      DependencyStack dependencyStack,
      Collection<ConstraintValue> constraintValues,
      ImmutableMap<BuckConfigKey, String> values) {
    for (Map.Entry<BuckConfigKey, String> entry : values.entrySet()) {
      if (!matches(buckConfig, entry.getKey(), entry.getValue())) {
        return false;
      }
    }
    if (constraintValues.isEmpty()) {
      // buckconfig only matcher, no need ask platform to match
      return true;
    }

    return targetPlatform.matchesAll(constraintValues, dependencyStack);
  }

  private static boolean matches(BuckConfig buckConfig, BuckConfigKey key, String value) {
    Optional<String> currentValue = buckConfig.getValue(key.getSection(), key.getProperty());
    return currentValue.map(curValue -> curValue.equals(value)).orElse(false);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("config_setting(");
    boolean first = true;
    if (!getValues().isEmpty()) {
      sb.append("values=").append(getValues());
      first = false;
    }
    if (!getConstraintValues().isEmpty()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append("constraint_values=").append(getConstraintValues());
    }
    sb.append(")");
    return sb.toString();
  }

  public static ConfigSettingSelectable of(
      ImmutableMap<BuckConfigKey, String> values, ImmutableSet<ConstraintValue> constraintValues) {
    return ImmutableConfigSettingSelectable.ofImpl(values, constraintValues);
  }
}
