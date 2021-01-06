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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.util.types.Unit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

class CellManager {

  private final Cell rootCell;
  private final ConcurrentHashMap<CanonicalCellName, Unit> cells = new ConcurrentHashMap<>();
  private final SymlinkCache symlinkCache;

  public CellManager(Cell rootCell, SymlinkCache symlinkCache) {
    this.rootCell = rootCell;
    this.symlinkCache = symlinkCache;
    symlinkCache.registerCell(rootCell);
  }

  void register(Cell cell) {
    if (!cells.containsKey(cell.getCanonicalName())) {
      cells.put(cell.getCanonicalName(), Unit.UNIT);
      symlinkCache.registerCell(cell);
    }
  }

  Cell getCell(CanonicalCellName cellName) {
    Cell cell = rootCell.getCell(cellName);
    register(cell);
    return cell;
  }

  void registerInputsUnderSymlinks(Path buildFile, TargetNode<?> node) throws IOException {
    Cell currentCell = getCell(node.getBuildTarget().getCell());
    symlinkCache.registerInputsUnderSymlinks(
        currentCell, getCell(node.getBuildTarget().getCell()), AbsPath.of(buildFile), node);
  }

  void close() {
    symlinkCache.close();
  }
}
