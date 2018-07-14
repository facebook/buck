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

package com.facebook.buck.core.model.targetgraph;

import com.google.common.collect.ImmutableMap;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Wrapper around {@code Map<String, Object>} that provides convenient methods to access values with
 * inferring types (so that the clients don't need to cast values manually.)
 */
public class RawAttributes {
  private final ImmutableMap<String, Object> attributes;

  public RawAttributes(ImmutableMap<String, Object> attributes) {
    this.attributes = attributes;
  }

  public ImmutableMap<String, Object> getAll() {
    return attributes;
  }

  /** @return the attribute value or <code>null</code> if such attribute doesn't exist */
  public @Nullable <T> T get(String attributeName) {
    @SuppressWarnings("unchecked")
    T value = (T) attributes.get(attributeName);
    return value;
  }

  /** @return the attribute value of the given default value if such attribute doesn't exist */
  public <T> T get(String attributeName, T defaultValue) {
    @SuppressWarnings("unchecked")
    T value = (T) attributes.get(attributeName);
    return value == null ? defaultValue : value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RawAttributes that = (RawAttributes) o;
    return Objects.equals(attributes, that.attributes);
  }

  @Override
  public int hashCode() {
    return attributes.hashCode();
  }
}
