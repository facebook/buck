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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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
    Optional<String> thisName = delegate.getCanonicalCellName(cellPath);
    if (thisName.isPresent()) {
      // A cell should be able to view into itself even if it doesn't explicitly specify it.
      this.declaredCellNames =
          ImmutableSet.copyOf(Sets.union(declaredCellNames, ImmutableSet.of(thisName.get())));
    } else {
      this.declaredCellNames = declaredCellNames;
    }
    this.cellPath = cellPath;
  }

  @Override
  public Optional<Path> getCellPath(Optional<String> cellName) {
    if (cellName.isPresent()) {
      if (declaredCellNames.contains(cellName.get())) {
        return delegate.getCellPath(cellName);
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.of(cellPath);
    }
  }

  @Override
  public ImmutableMap<String, Path> getCellPaths() {
    return ImmutableMap.copyOf(
        Maps.filterKeys(delegate.getCellPaths(), declaredCellNames::contains));
  }

  @Override
  public Optional<String> getCanonicalCellName(Path cellPath) {
    // TODO(cjhopman): This should verify that this path is visible in this view.
    return delegate.getCanonicalCellName(cellPath);
  }
}
