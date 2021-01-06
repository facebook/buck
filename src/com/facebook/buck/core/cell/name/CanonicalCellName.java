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

package com.facebook.buck.core.cell.name;

import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Provides a canonical identifier for a {@link com.facebook.buck.core.cell.Cell}. There is a 1-1
 * mapping between these.
 *
 * <p>These should not be constructed by users and instead should only be acquired from a {@link
 * com.facebook.buck.core.cell.Cell} or via a {@link
 * com.facebook.buck.core.cell.nameresolver.CellNameResolver}.
 */
@Value.Immutable(prehash = true, builder = false, copy = false, intern = true)
@BuckStylePrehashedValue
@JsonDeserialize
public abstract class CanonicalCellName implements Comparable<CanonicalCellName> {
  // This little class is to avoid referencing the subclass in our static initializer.
  private static class Holder {
    public static final CanonicalCellName ROOT_CELL =
        ImmutableCanonicalCellName.of(Optional.empty());
    public static final CanonicalCellName NOT_A_CELL =
        ImmutableCanonicalCellName.of(Optional.of("not-a-cell"));
  }

  public static CanonicalCellName rootCell() {
    return Holder.ROOT_CELL;
  }

  /**
   * This cell object is used when there's no cell, but {@link CanonicalCellName} object is needed.
   * This cell name is never resolved successfully to a cell.
   */
  public static CanonicalCellName unsafeNotACell() {
    return Holder.NOT_A_CELL;
  }

  /**
   * This should be used to mark places that are hardcoding the root cell's canonical name in a way
   * that doesn't ensure that it's correct.
   */
  public static CanonicalCellName unsafeRootCell() {
    return rootCell();
  }

  // TODO(cjhopman): We should change this to derive the legacy name from the name instead of the
  // reverse.
  /** Returns the underlying name in the legacy {@code Optional<String>} format. */
  @JsonProperty("name")
  public abstract Optional<String> getLegacyName();

  /** Returns the name in a human-readable form. */
  public String getName() {
    return getLegacyName().orElse("");
  }

  @Override
  public int compareTo(CanonicalCellName o) {
    return Optionals.compare(getLegacyName(), o.getLegacyName());
  }

  @Override
  public String toString() {
    return getName();
  }

  public static CanonicalCellName of(Optional<String> name) {
    return ImmutableCanonicalCellName.of(name);
  }

  /**
   * Used for creating CanonicalCellName in places where we aren't guaranteed to be handling
   * canonicalization correctly. This is only used to mark those places so that they are easier to
   * find, the behavior is unchanged.
   */
  public static CanonicalCellName unsafeOf(Optional<String> name) {
    return ImmutableCanonicalCellName.of(name);
  }
}
