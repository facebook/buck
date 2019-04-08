/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class CellManager {

  private final SymlinkCache symlinkCache;
  private final Map<Path, Cell> cells = new ConcurrentHashMap<>();

  public CellManager(SymlinkCache symlinkCache) {
    this.symlinkCache = symlinkCache;
  }

  void register(Cell cell) {
    Path root = cell.getFilesystem().getRootPath();
    if (!cells.containsKey(root)) {
      cells.put(root, cell);
      symlinkCache.registerCell(root, cell);
    }
  }

  Cell getCell(UnconfiguredBuildTargetView target) {
    Cell cell = cells.get(target.getCellPath());
    if (cell != null) {
      return cell;
    }

    for (Cell possibleOwner : cells.values()) {
      Optional<Cell> maybe = possibleOwner.getCellIfKnown(target);
      if (maybe.isPresent()) {
        register(maybe.get());
        return maybe.get();
      }
    }
    throw new HumanReadableException(
        "From %s, unable to find cell rooted at: %s", target, target.getCellPath());
  }

  Cell getCell(BuildTarget target) {
    return getCell(target.getUnconfiguredBuildTargetView());
  }

  void registerInputsUnderSymlinks(Path buildFile, TargetNode<?> node) throws IOException {
    Cell currentCell = getCell(node.getBuildTarget());
    symlinkCache.registerInputsUnderSymlinks(
        currentCell, getCell(node.getBuildTarget()), buildFile, node);
  }

  void close() {
    symlinkCache.close();
  }
}
