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

package com.facebook.buck.core.cell.impl;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.filesystem.RecursiveFileMatcher;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import org.immutables.value.Value;

@BuckStyleValue
abstract class CellImpl implements Cell {

  @Override
  @Value.Auxiliary
  public abstract ImmutableSortedSet<AbsPath> getKnownRootsOfAllCells();

  @Override
  @Value.Auxiliary
  public abstract CanonicalCellName getCanonicalName();

  @Override
  @Value.Auxiliary
  public abstract ProjectFilesystem getFilesystem();

  @Override
  @Value.Auxiliary
  @Value.Derived
  public ProjectFilesystemView getFilesystemViewForSourceFiles() {
    ProjectFilesystem filesystem = getFilesystem();
    ImmutableSet.Builder<PathMatcher> ignores =
        ImmutableSet.builderWithExpectedSize(filesystem.getBlacklistedPaths().size() + 1);
    ignores.addAll(filesystem.getBlacklistedPaths());
    ignores.add(RecursiveFileMatcher.of(RelPath.of(filesystem.getBuckPaths().getBuckOut())));
    for (AbsPath subCellRoots : getKnownRootsOfAllCells()) {
      if (!subCellRoots.equals(getRoot())) {
        ignores.add(
            RecursiveFileMatcher.of(RelPath.of(filesystem.relativize(subCellRoots).getPath())));
      }
    }
    return filesystem.asView().withView(filesystem.getPath(""), ignores.build());
  }

  @Override
  public abstract BuckConfig getBuckConfig();

  /** See {@link BuckConfig#getView(Class)} */
  @Override
  public <T extends ConfigView<BuckConfig>> T getBuckConfigView(Class<T> cls) {
    return getBuckConfig().getView(cls);
  }

  @Override
  @Value.Auxiliary
  public abstract CellProvider getCellProvider();

  @Override
  @Value.Auxiliary
  public abstract ToolchainProvider getToolchainProvider();

  @Override
  public AbsPath getRoot() {
    return getFilesystem().getRootPath();
  }

  @Override
  public Cell getCellIgnoringVisibilityCheck(Path cellPath) {
    return getCellProvider().getCellByPath(cellPath);
  }

  @Override
  public Cell getCell(Path cellPath) {
    if (!getKnownRootsOfAllCells().contains(AbsPath.of(cellPath))) {
      throw new HumanReadableException(
          "Unable to find repository rooted at %s. Known roots are:\n  %s",
          cellPath, Joiner.on(",\n  ").join(getKnownRootsOfAllCells()));
    }
    return getCellIgnoringVisibilityCheck(cellPath);
  }

  @Override
  public Cell getCell(CanonicalCellName cellName) {
    return getCellProvider().getCellByCanonicalCellName(cellName);
  }

  @Override
  public ImmutableList<Cell> getAllCells() {
    return RichStream.from(getKnownRootsOfAllCells())
        .map(AbsPath::getPath)
        .concat(RichStream.of(getRoot().getPath()))
        .distinct()
        .map(getCellProvider()::getCellByPath)
        .toImmutableList();
  }

  @Override
  public ImmutableMap<AbsPath, Cell> getLoadedCells() {
    return getCellProvider().getLoadedCells();
  }

  @Override
  public abstract CellPathResolver getCellPathResolver();

  @Override
  public abstract NewCellPathResolver getNewCellPathResolver();

  @Override
  public abstract CellNameResolver getCellNameResolver();
}
