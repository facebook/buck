/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcconfig;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Objects;

/**
 * The value of an entry in a xcconfig file
 */
public class PredicatedConfigValue {
  public final String key;
  public final ImmutableSortedSet<Condition> conditions;
  public final ImmutableList<TokenValue> valueTokens;

  public PredicatedConfigValue(
      String key, ImmutableSortedSet<Condition> conditions, ImmutableList<TokenValue> valueTokens) {
    this.key = key;
    this.conditions = conditions;
    this.valueTokens = valueTokens;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PredicatedConfigValue)) {
      return false;
    }

    PredicatedConfigValue that = (PredicatedConfigValue) other;
    return Objects.equals(this.key, that.key)
        && Objects.equals(this.conditions, that.conditions)
        && Objects.equals(this.valueTokens, that.valueTokens);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, conditions, valueTokens);
  }

  @Override
  public String toString() {
    StringBuilder line = new StringBuilder();
    line.append(key);
    if (!conditions.isEmpty()) {
      line.append('[');
      line.append(Joiner.on(',').join(conditions));
      line.append(']');
    }
    line.append(" = ");
    line.append(Joiner.on("").join(valueTokens));
    return line.toString();
  }
}
