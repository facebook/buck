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

package com.facebook.buck.core.cell.name;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

/** Describes a cell name relative to another cell. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRelativeCellName {
  public static final char CELL_NAME_COMPONENT_SEPARATOR = '/';
  public static final String ALL_CELLS_SPECIAL_STRING = "*";

  public static final RelativeCellName ROOT_CELL_NAME = RelativeCellName.of(ImmutableList.of());
  public static final RelativeCellName ALL_CELLS_SPECIAL_NAME =
      RelativeCellName.of(ImmutableSet.of(ALL_CELLS_SPECIAL_STRING));

  public abstract ImmutableList<String> getComponents();

  public Optional<String> getLegacyName() {
    if (getComponents().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Joiner.on(CELL_NAME_COMPONENT_SEPARATOR).join(getComponents()));
  }

  public static RelativeCellName fromComponents(String... strings) {
    return RelativeCellName.of(FluentIterable.from(strings));
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(RelativeCellName.class).addValue(getLegacyName()).toString();
  }

  @Value.Check
  protected void checkComponents() {
    // Special case for the single-component 'all cells' designator'
    if (getComponents().size() == 1 && getComponents().get(0).equals(ALL_CELLS_SPECIAL_STRING)) {
      return;
    }
    ImmutableSet<String> prohibitedSubstrings =
        ImmutableSet.of(
            Character.toString(CELL_NAME_COMPONENT_SEPARATOR), ALL_CELLS_SPECIAL_STRING);
    for (String prohibited : prohibitedSubstrings) {
      for (String component : getComponents()) {
        Preconditions.checkState(
            !component.contains(prohibited),
            "Cell name component %s in cell %s should not contain %s.",
            component,
            getComponents(),
            prohibited);
      }
    }
  }
}
