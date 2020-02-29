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
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.CellRelativePath;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public interface CellPathResolver {

  /** Cell name resolver works in context of some cell. This function returns current cell. */
  default CanonicalCellName getCurrentCellName() {
    return getCellNameResolver().getCurrentCellName();
  }

  /**
   * Provides access to the {@link CellNameResolver} for this cell. This is to assist in migration
   * to the new name/path resolvers.
   */
  CellNameResolver getCellNameResolver();

  /**
   * Provides access to the {@link NewCellPathResolver}. This is to assist in migration to the new
   * name/path resolvers.
   */
  NewCellPathResolver getNewCellPathResolver();

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
  Path getCellPathOrThrow(CanonicalCellName cellName);

  /** Resolve a cell-relative path to an absolute path. */
  Path resolveCellRelativePath(CellRelativePath cellRelativePath);

  /**
   * @return absolute paths to all cells this resolver knows about. The key is the name of the cell
   *     in the root cell's config (this is not necessarily the canonical name).
   */
  ImmutableMap<String, AbsPath> getCellPathsByRootCellExternalName();

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

  /** Abs-path version of {@link #getCanonicalCellName(Path)}. */
  default Optional<String> getCanonicalCellName(AbsPath cellPath) {
    return getCanonicalCellName(cellPath.getPath());
  }

  /** @return paths to roots of all cells known to this resolver. */
  ImmutableSortedSet<AbsPath> getKnownRoots();
}
