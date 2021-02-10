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
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import javax.annotation.Nonnull;

/** A {@link CellBoundaryChecker} which throws when targets violate cell boundaries. */
public class ThrowingCellBoundaryChecker implements CellBoundaryChecker {

  private final Cells cells;

  private final LoadingCache<AbsPath, CanonicalCellName> cellForPath =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<AbsPath, CanonicalCellName>() {
                @Override
                public CanonicalCellName load(@Nonnull AbsPath path) throws Exception {
                  return cellForPath.get(
                      Preconditions.checkNotNull(
                          path.getParent(), "path %s is not rooted in any cell", path));
                }
              });

  public ThrowingCellBoundaryChecker(Cells cells) {
    this.cells = cells;
    // Seed cache with cell roots.
    cells
        .getAllCells()
        .forEach(cell -> this.cellForPath.put(cell.getRoot(), cell.getCanonicalName()));
  }

  @Override
  public void enforceCellBoundary(BuildTarget target) {
    Cell expectedCell = cells.getCell(target.getCell());
    AbsPath packagePath =
        expectedCell
            .getRoot()
            .resolve(
                target
                    .getBaseName()
                    .getPath()
                    .toPath(expectedCell.getFilesystem().getFileSystem()));
    CanonicalCellName actualCellname = cellForPath.getUnchecked(packagePath);
    if (!actualCellname.equals(target.getCell())) {
      throw new HumanReadableException(
          "Parsed target %s from cell \"%s\", but is owned by cell \"%s\"",
          target, target.getCell(), actualCellname);
    }
  }
}
