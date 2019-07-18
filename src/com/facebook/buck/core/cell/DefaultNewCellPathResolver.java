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
package com.facebook.buck.core.cell;

import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Implementation of {@link NewCellPathResolver} based on the path to name map (this is assumed to
 * be 1-1).
 */
@BuckStyleValue
public abstract class DefaultNewCellPathResolver implements NewCellPathResolver {
  // Note: don't expose this map, doing that would allow things to look up CanonicalCellName from
  // Path in places where they shouldn't do that.
  abstract ImmutableMap<Path, CanonicalCellName> getPathToNameMap();

  @Value.Derived
  ImmutableSortedMap<CanonicalCellName, Path> getCellToPathMap() {
    ImmutableSortedMap<CanonicalCellName, Path> cellToPathMap =
        getPathToNameMap().entrySet().stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), e -> e.getValue(), e -> e.getKey()));
    return cellToPathMap;
  }

  @Override
  public Path getCellPath(CanonicalCellName cellName) {
    return getCellToPathMap().get(cellName);
  }

  @Override
  public CanonicalCellName getCanonicalCellName(Path path) {
    CanonicalCellName canonicalCellName = getPathToNameMap().get(path);
    if (canonicalCellName == null) {
      throw new RuntimeException(String.format("No known cell with path %s.", path));
    }
    return canonicalCellName;
  }
}
