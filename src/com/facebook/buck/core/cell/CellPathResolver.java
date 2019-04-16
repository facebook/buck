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

import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public interface CellPathResolver {
  /**
   * @param cellName name of cell, Optional.empty() for root cell.
   * @return Absolute path to the physical location of the cell, or {@code Optional.empty()} if the
   *     cell name cannot be resolved.
   */
  Optional<Path> getCellPath(Optional<String> cellName);

  /**
   * @param cellName name of cell, Optional.empty() for root cell.
   * @return Absolute path to the physical location of the cell
   * @throws AssertionError if cell is not known
   */
  Path getCellPathOrThrow(Optional<String> cellName);

  /**
   * @return Absolute path to the physical location of the cell that contains the provided target
   * @throws AssertionError if cell is not known
   */
  Path getCellPathOrThrow(UnflavoredBuildTargetView buildTarget);

  /** @return absolute paths to all cells this resolver knows about. */
  ImmutableMap<String, Path> getCellPaths();

  /**
   * Returns a cell name that can be used to refer to the cell at the given path.
   *
   * <p>Returns {@code Optional.empty()} if the path refers to the root cell. Returns the
   * lexicographically smallest name if the cell path has multiple names.
   *
   * <p>Note: this is not the inverse of {@link #getCellPath(Optional)}, which returns the current,
   * rather than the root, cell path if the cell name is empty.
   *
   * @throws IllegalArgumentException if cell path is not known to the CellPathResolver.
   */
  Optional<String> getCanonicalCellName(Path cellPath);

  /** @return paths to roots of all cells known to this resolver. */
  ImmutableSortedSet<Path> getKnownRoots();
}
