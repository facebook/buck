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

package com.facebook.buck.core.select;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/**
 * Keeps mapping of a single <code>select</code> expression. The keys of a mapping are {@link
 * SelectorKey}s and the values can be of an arbitrary type.
 */
public class Selector<T> {

  private final ImmutableMap<SelectorKey, T> conditions;
  private final ImmutableSet<SelectorKey> nullConditions;
  private final String noMatchMessage;
  private final boolean hasDefaultCondition;

  /**
   * Creates a new Selector with a custom error message for a situation when no conditions match.
   */
  public Selector(
      ImmutableMap<SelectorKey, T> conditions,
      ImmutableSet<SelectorKey> nullConditions,
      String noMatchMessage,
      boolean hasDefaultCondition) {
    this.conditions = conditions;
    this.nullConditions = nullConditions;
    this.noMatchMessage = noMatchMessage;
    this.hasDefaultCondition = hasDefaultCondition;
  }

  public ImmutableMap<SelectorKey, T> getConditions() {
    return conditions;
  }

  public ImmutableSet<SelectorKey> getNullConditions() {
    return nullConditions;
  }

  /**
   * Returns the default value - value to use when none of the conditions match.
   *
   * @return the value provided in the default condition or <code>null</code> if it wasn't provided.
   */
  public @Nullable T getDefaultConditionValue() {
    return conditions.get(SelectorKey.DEFAULT);
  }

  /** Returns whether or not this selector has a default condition. */
  public boolean hasDefaultCondition() {
    return hasDefaultCondition;
  }

  /**
   * Returns a custom message that needs to be shown to a user when no condition matches.
   *
   * @return the custom message or an empty string if the message is not declared.
   */
  public String getNoMatchMessage() {
    return noMatchMessage;
  }
}
