/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.model;

import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Provides a canonical identifier for a {@link com.facebook.buck.core.cell.Cell}. There is a 1-1
 * mapping between these.
 *
 * <p>These should not be constructed by users and instead should only be acquired from a {@link
 * com.facebook.buck.core.cell.Cell} or via a {@link com.facebook.buck.core.cell.CellNameResolver}.
 */
@Value.Immutable(prehash = true, builder = false, copy = false)
@BuckStyleValue
@JsonDeserialize
public interface CanonicalCellName extends Comparable<CanonicalCellName> {
  CanonicalCellName ROOT_CELL = new ImmutableCanonicalCellName(Optional.empty());

  // TODO(cjhopman): We should change this to derive the legacy name from the name instead of the
  // reverse.

  /** Returns the underlying name in the legacy {@code Optional<String>} format. */
  @JsonProperty("name")
  Optional<String> getLegacyName();

  /** Returns the name in a human-readable form. */
  default String getName() {
    return getLegacyName().orElse("");
  }

  @Override
  default int compareTo(CanonicalCellName o) {
    return Optionals.compare(getLegacyName(), o.getLegacyName());
  }
}
