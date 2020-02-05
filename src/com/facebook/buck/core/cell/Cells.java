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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/** Access all cells. */
public class Cells {
  private final Cell rootCell;

  public Cells(Cell rootCell) {
    Preconditions.checkArgument(rootCell.getCanonicalName() == CanonicalCellName.rootCell());
    this.rootCell = rootCell;
  }

  public Cell getRootCell() {
    return rootCell;
  }

  public Cell getCell(CanonicalCellName cellName) {
    return rootCell.getCell(cellName);
  }

  public ImmutableList<Cell> getAllCells() {
    return rootCell.getAllCells();
  }

  public CellProvider getCellProvider() {
    return rootCell.getCellProvider();
  }
}
