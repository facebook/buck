/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import java.util.Optional;
import org.immutables.value.Value;

/** Describes a cell name relative to another cell. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractCellName {
  public static final String ALL_CELLS_SPECIAL_STRING = "*";

  public static final CellName ROOT_CELL_NAME = CellName.of("");
  public static final CellName ALL_CELLS_SPECIAL_NAME = CellName.of(ALL_CELLS_SPECIAL_STRING);

  public abstract String getName();

  public Optional<String> getLegacyName() {
    if (getName().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(getName());
  }

  @Override
  public String toString() {
    return getName();
  }

  @Value.Check
  protected void checkComponents() {
    // Special case for the single-component 'all cells' designator'
    String name = getName();
    if (name.equals(ALL_CELLS_SPECIAL_STRING)) {
      return;
    }
    Preconditions.checkState(
        !name.contains("/") && !name.contains(ALL_CELLS_SPECIAL_STRING),
        "Cell name %s should not contain * or / characters.",
        name);
  }
}
