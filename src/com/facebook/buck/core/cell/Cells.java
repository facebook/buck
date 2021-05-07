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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;

/** Access all cells. */
public class Cells {
  private final Cell rootCell;
  private final CellProvider cellProvider;

  public Cells(CellProvider cellProvider) {
    this.rootCell = cellProvider.getCellByCanonicalCellName(CanonicalCellName.rootCell());
    this.cellProvider = cellProvider;
  }

  public Cell getRootCell() {
    return rootCell;
  }

  /** Root cell buckconfig. */
  public BuckConfig getBuckConfig() {
    return rootCell.getBuckConfig();
  }

  public Cell getCell(CanonicalCellName cellName) {
    return rootCell.getCell(cellName);
  }

  /** Returns a list of all cells. */
  public ImmutableList<Cell> getAllCells() {
    return cellProvider.getAllCells();
  }

  public CellProvider getCellProvider() {
    return cellProvider;
  }

  /** @return Path of the topmost cell's path that roots all other cells */
  public AbsPath getSuperRootPath() {
    AbsPath cellRoot = getRootCell().getRoot();
    ImmutableSortedSet<AbsPath> allRoots =
        cellProvider.getRootCellCellPathResolver().getKnownRoots();
    AbsPath path = cellRoot.getRoot();

    // check if supercell is a root folder, like '/' or 'C:\'
    if (allRoots.contains(path)) {
      return path;
    }

    // There is an assumption that there is exactly one cell with a path that prefixes all other
    // cell paths. So just try to find the cell with the shortest common path.
    for (Path next : cellRoot.getPath()) {
      path = path.resolve(next);
      if (allRoots.contains(path)) {
        return path;
      }
    }
    throw new IllegalStateException(
        "Unreachable: at least one path should be in getKnownRoots(), including root cell '"
            + cellRoot.toString()
            + "'; known roots = ["
            + allRoots.stream().map(Objects::toString).collect(Collectors.joining(", "))
            + "]");
  }
}
