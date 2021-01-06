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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Implementation of {@link NewCellPathResolver} based on the path to name map (this is assumed to
 * be 1-1).
 */
@BuckStyleValue
public abstract class DefaultNewCellPathResolver implements NewCellPathResolver {
  // Note: don't expose this map, doing that would allow things to look up CanonicalCellName from
  // Path in places where they shouldn't do that.
  abstract ImmutableMap<AbsPath, CanonicalCellName> getPathToNameMap();

  @Value.Derived
  ImmutableSortedMap<CanonicalCellName, AbsPath> getCellToPathMap() {
    ImmutableSortedMap<CanonicalCellName, AbsPath> cellToPathMap =
        getPathToNameMap().entrySet().stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), e -> e.getValue(), e -> e.getKey()));
    return cellToPathMap;
  }

  @Override
  public Path getCellPath(CanonicalCellName cellName) {
    AbsPath path = getCellToPathMap().get(cellName);
    if (path == null) {
      throw new RuntimeException(
          String.format(
              "Cell '%s' does not have a mapping to a path. Known cells are {%s}",
              cellName, formatKnownCells()));
    }
    return path.getPath();
  }

  @Override
  public CanonicalCellName getCanonicalCellName(Path path) {
    CanonicalCellName canonicalCellName = getPathToNameMap().get(AbsPath.of(path));
    if (canonicalCellName == null) {
      throw new RuntimeException(
          String.format(
              "No known cell with path %s. Known cells are {%s}", path, formatKnownCells()));
    }
    return canonicalCellName;
  }

  public static ImmutableDefaultNewCellPathResolver of(
      Map<? extends AbsPath, ? extends CanonicalCellName> pathToNameMap) {
    return ImmutableDefaultNewCellPathResolver.of(pathToNameMap);
  }

  private String formatKnownCells() {
    return getCellToPathMap().entrySet().stream()
        .map(e -> String.format("%s: %s", e.getKey(), e.getValue()))
        .collect(Collectors.joining(", "));
  }
}
