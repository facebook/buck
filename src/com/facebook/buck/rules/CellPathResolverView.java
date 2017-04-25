/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Optional;

/**
 * View of a subset of cells of a cell path resolver.
 *
 * <p>Views are used for non-root cells, to ensure that only the subset of cell names that the cell
 * declares are visible within that cell.
 */
public final class CellPathResolverView implements CellPathResolver {
  private final CellPathResolver delegate;
  private final ImmutableSet<String> declaredCellNames;
  private final Path cellPath;

  public CellPathResolverView(
      CellPathResolver delegate, ImmutableSet<String> declaredCellNames, Path cellPath) {
    this.delegate = delegate;
    this.declaredCellNames = declaredCellNames;
    this.cellPath = cellPath;
  }

  @Override
  public Path getCellPath(Optional<String> cellName) {
    if (cellName.isPresent()) {
      if (declaredCellNames.contains(cellName.get())) {
        return delegate.getCellPath(cellName);
      } else {
        throw new HumanReadableException(
            "In cell rooted at %s: cell name '%s' is not visible.", cellPath, cellName.get());
      }
    } else {
      return cellPath;
    }
  }

  @Override
  public ImmutableMap<String, Path> getCellPaths() {
    return ImmutableMap.copyOf(
        Maps.filterKeys(delegate.getCellPaths(), declaredCellNames::contains));
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    return delegate.getCanonicalCellName(cellPath);
  }
}
