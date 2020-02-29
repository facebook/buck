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
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/** A cache for caching objects per build state with multiple {@link Cell}s. */
class PerBuildStateCache {

  private final int parsingThreads;

  private final ConcurrentMap<CanonicalCellName, CellState> cellPathToCellState;

  /** Cache mapping the package file path to the {@link Package} defined in that file. */
  private final PackageCache packageCache;

  PerBuildStateCache(int parsingThreads) {
    this.parsingThreads = parsingThreads;

    this.cellPathToCellState = new ConcurrentHashMap<>();

    this.packageCache = new PackageCache();
  }

  public PackageCache getPackageCache() {
    return packageCache;
  }

  @Nullable
  private CellState getCellState(Cell cell) {
    return cellPathToCellState.get(cell.getCanonicalName());
  }

  private CellState getOrCreateCellState(Cell cell) {
    return cellPathToCellState.computeIfAbsent(
        cell.getCanonicalName(), canonicalCellName -> new CellState(parsingThreads));
  }

  /** Per {@link Cell} threadsafe packageCache for {@link Package}s. */
  private class CellState {

    /** Used as an unbounded packageCache to stored computed packages by package file path. */
    private final ConcurrentMapCache<AbsPath, Package> packages;

    CellState(int parsingThreads) {
      this.packages = new ConcurrentMapCache<>(parsingThreads);
    }

    Optional<Package> lookupPackage(AbsPath packageFile) {
      return Optional.ofNullable(packages.getIfPresent(packageFile));
    }

    Package putPackageIfNotPresent(AbsPath packageFile, Package pkg) {
      return packages.putIfAbsentAndGet(packageFile, pkg);
    }
  }

  /**
   * A {@link PipelineNodeCache} compatible packageCache mapping a {@code packageFile} path to the
   * associated {@link Package}. Each {@link Cell} contains it's own PackageCache.
   */
  class PackageCache implements PipelineNodeCache.Cache<AbsPath, Package> {
    @Override
    public Optional<Package> lookupComputedNode(
        Cell cell, AbsPath packageFile, BuckEventBus eventBus) throws BuildTargetException {
      CellState state = getCellState(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupPackage(packageFile);
    }

    @Override
    public Package putComputedNodeIfNotPresent(
        Cell cell,
        AbsPath packageFile,
        Package pkg,
        boolean targetIsConfiguration,
        BuckEventBus eventBus)
        throws BuildTargetException {
      Preconditions.checkState(!targetIsConfiguration);

      return getOrCreateCellState(cell).putPackageIfNotPresent(packageFile, pkg);
    }
  }
}
