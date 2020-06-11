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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * {@link com.facebook.buck.core.select.Selector} but with labels resolved to {@link
 * ConfigSettingSelectable}.
 */
public class SelectorResolved<T> {

  private final ImmutableMap<SelectorKey, Resolved<T>> conditions;
  private final String noMatchMessage;

  public SelectorResolved(
      ImmutableMap<SelectorKey, Resolved<T>> conditions, String noMatchMessage) {
    this.conditions = conditions;
    this.noMatchMessage = noMatchMessage;
  }

  /** A pair of resolve selector key and selector entry output. */
  public static class Resolved<T> {
    private final ConfigSettingSelectable selectable;
    /** Empty encodes null. */
    private final Optional<T> output;

    public Resolved(ConfigSettingSelectable selectable, Optional<T> output) {
      this.selectable = selectable;
      this.output = output;
    }

    public ConfigSettingSelectable getSelectable() {
      return selectable;
    }

    public Optional<T> getOutput() {
      return output;
    }
  }

  public ImmutableMap<SelectorKey, Resolved<T>> getConditions() {
    return conditions;
  }

  public String getNoMatchMessage() {
    return noMatchMessage;
  }

  @Nullable
  public T getDefaultConditionValue() {
    Resolved<T> resolved = conditions.get(SelectorKey.DEFAULT);
    return resolved != null ? resolved.output.orElse(null) : null;
  }

  /** Unresolve. */
  public Selector<T> toSelector() {
    ImmutableMap.Builder<SelectorKey, T> conditions = ImmutableMap.builder();
    ImmutableSet.Builder<SelectorKey> nullConditions = ImmutableSet.builder();

    for (Map.Entry<SelectorKey, Resolved<T>> entry : this.conditions.entrySet()) {
      Optional<T> output = entry.getValue().output;
      if (output.isPresent()) {
        conditions.put(entry.getKey(), output.get());
      } else {
        nullConditions.add(entry.getKey());
      }
    }

    return new Selector<>(conditions.build(), nullConditions.build(), noMatchMessage);
  }
}
