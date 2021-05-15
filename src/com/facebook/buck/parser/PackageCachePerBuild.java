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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * A {@link PipelineNodeCache} compatible packageCache mapping a {@code packageFile} path to the
 * associated {@link Package}. Each {@link Cell} contains it's own PackageCache.
 */
class PackageCachePerBuild implements PipelineNodeCache.Cache<ForwardRelPath, Package> {

  /** Per {@link Cell} threadsafe packageCache for {@link Package}s. */
  private static class CellState {

    /** Used as an unbounded packageCache to stored computed packages by package file path. */
    private final ConcurrentMapCache<AbsPath, Package> packages;

    CellState() {
      this.packages = new ConcurrentMapCache<>();
    }

    Optional<Package> lookupPackage(AbsPath packageFile) {
      return Optional.ofNullable(packages.getIfPresent(packageFile));
    }

    Package putPackageIfNotPresent(AbsPath packageFile, Package pkg) {
      return packages.putIfAbsentAndGet(packageFile, pkg);
    }
  }

  private final ConcurrentMap<CanonicalCellName, CellState> cellPathToCellState =
      new ConcurrentHashMap<>();

  @Nullable
  private CellState getCellState(Cell cell) {
    return cellPathToCellState.get(cell.getCanonicalName());
  }

  private CellState getOrCreateCellState(Cell cell) {
    return cellPathToCellState.computeIfAbsent(
        cell.getCanonicalName(), canonicalCellName -> new CellState());
  }

  @Override
  public Optional<Package> lookupComputedNode(Cell cell, ForwardRelPath packageFile)
      throws BuildTargetException {
    CellState state = getCellState(cell);
    if (state == null) {
      return Optional.empty();
    }
    AbsPath packageFileAbs = cell.getRoot().resolve(packageFile);
    return state.lookupPackage(packageFileAbs);
  }

  @Override
  public Package putComputedNodeIfNotPresent(
      Cell cell, ForwardRelPath packageFile, Package pkg, boolean targetIsConfiguration)
      throws BuildTargetException {
    Preconditions.checkState(!targetIsConfiguration);
    AbsPath packageFileAbs = cell.getRoot().resolve(packageFile);
    return getOrCreateCellState(cell).putPackageIfNotPresent(packageFileAbs, pkg);
  }
}
