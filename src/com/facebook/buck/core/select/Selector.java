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

package com.facebook.buck.core.select;

import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Keeps mapping of a single <code>select</code> expression. The keys of a mapping are {@link
 * SelectorKey}s and the values can be of an arbitrary type.
 */
public class Selector<T> {

  private final ImmutableMap<SelectorKey, T> conditions;
  private final ImmutableSet<SelectorKey> nullConditions;
  private final String noMatchMessage;

  /**
   * Creates a new Selector with a custom error message for a situation when no conditions match.
   */
  public Selector(
      ImmutableMap<SelectorKey, T> conditions,
      ImmutableSet<SelectorKey> nullConditions,
      String noMatchMessage) {
    this.conditions = conditions;
    this.nullConditions = nullConditions;
    this.noMatchMessage = noMatchMessage;
  }

  public static <T> Selector<T> onlyDefault(T element) {
    return new Selector<>(ImmutableMap.of(SelectorKey.DEFAULT, element), ImmutableSet.of(), "");
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
    return conditions.containsKey(SelectorKey.DEFAULT)
        || nullConditions.contains(SelectorKey.DEFAULT);
  }

  /**
   * Returns a custom message that needs to be shown to a user when no condition matches.
   *
   * @return the custom message or an empty string if the message is not declared.
   */
  public String getNoMatchMessage() {
    return noMatchMessage;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Selector<?> selector = (Selector<?>) o;
    return conditions.equals(selector.conditions)
        && nullConditions.equals(selector.nullConditions)
        && noMatchMessage.equals(selector.noMatchMessage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(conditions, nullConditions, noMatchMessage);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("select({");
    boolean first = true;

    for (Map.Entry<SelectorKey, T> entry : conditions.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append('"').append(entry.getKey()).append('"');
      sb.append(": ");
      sb.append(entry.getValue());
    }

    for (SelectorKey entry : nullConditions) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append('"').append(entry).append('"');
      sb.append(": ");
      sb.append("None");
    }

    sb.append("}");
    if (noMatchMessage != null) {
      sb.append(", no_match_message=");
      sb.append('"').append(noMatchMessage).append('"');
    }

    sb.append(")");

    return sb.toString();
  }

  /** Transform all items with given function. */
  public <U, E extends Exception> Selector<U> mapValuesThrowing(ThrowingFunction<T, U, E> function)
      throws E {
    ImmutableMap.Builder<SelectorKey, U> conditions =
        ImmutableMap.builderWithExpectedSize(this.conditions.size());
    for (Map.Entry<SelectorKey, T> condition : this.conditions.entrySet()) {
      conditions.put(condition.getKey(), function.apply(condition.getValue()));
    }
    return new Selector<>(conditions.build(), nullConditions, noMatchMessage);
  }
}
