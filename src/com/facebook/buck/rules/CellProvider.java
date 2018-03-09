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
package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.annotation.Nullable;

public final class CellProvider {
  private final LoadingCache<Path, Cell> cells;

  /**
   * Create a cell provider with a specific cell loader, and optionally a special factory function
   * for the root cell.
   *
   * <p>The indirection for passing in CellProvider allows cells to reference the current
   * CellProvider object.
   */
  CellProvider(
      Function<CellProvider, CacheLoader<Path, Cell>> cellCacheLoader,
      @Nullable Function<CellProvider, Cell> rootCellLoader) {
    this.cells = CacheBuilder.newBuilder().build(cellCacheLoader.apply(this));
    if (rootCellLoader != null) {
      Cell rootCell = rootCellLoader.apply(this);
      cells.put(rootCell.getRoot(), rootCell);
    }
  }

  public Cell getCellByPath(Path path) {
    try {
      return cells.get(path);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw new HumanReadableException(e.getCause(), "Failed to load Cell at: %s", path);
      } else if (e.getCause() instanceof InterruptedException) {
        throw new RuntimeException("Interrupted while loading Cell: " + path, e);
      } else {
        throw new IllegalStateException(
            "Unexpected checked exception thrown from cell loader.", e.getCause());
      }
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  public ImmutableMap<Path, Cell> getLoadedCells() {
    return ImmutableMap.copyOf(cells.asMap());
  }

  public Cell getBuildTargetCell(BuildTarget buildTarget) {
    return getCellByPath(buildTarget.getCellPath());
  }
}
