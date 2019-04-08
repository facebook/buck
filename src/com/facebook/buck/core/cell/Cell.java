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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 *
 * <p>Should only be constructed by {@link CellProvider}.
 */
public interface Cell {

  ImmutableSortedSet<Path> getKnownRoots();

  Optional<String> getCanonicalName();

  ProjectFilesystem getFilesystem();

  /**
   * @return {@link ProjectFilesystemView} that filters out ignores specified for this cell, like
   *     blacklisted paths and buck-out, to iterate over files which are potential direct sources,
   *     build files, etc.
   */
  ProjectFilesystemView getFilesystemViewForSourceFiles();

  BuckConfig getBuckConfig();

  /** See {@link BuckConfig#getView(Class)} */
  <T extends ConfigView<BuckConfig>> T getBuckConfigView(Class<T> cls);

  CellProvider getCellProvider();

  ToolchainProvider getToolchainProvider();

  Path getRoot();

  Cell getCellIgnoringVisibilityCheck(Path cellPath);

  Cell getCell(Path cellPath);

  Cell getCell(UnconfiguredBuildTargetView target);

  Cell getCell(BuildTarget target);

  Optional<Cell> getCellIfKnown(BuildTarget target);

  Optional<Cell> getCellIfKnown(UnconfiguredBuildTargetView target);

  /**
   * Returns a list of all cells, including this cell. If this cell is the root, getAllCells will
   * necessarily return all possible cells that this build may interact with, since the root cell is
   * required to declare a mapping for all cell names.
   */
  ImmutableList<Cell> getAllCells();

  /** @return all loaded {@link Cell}s that are children of this {@link Cell}. */
  ImmutableMap<Path, Cell> getLoadedCells();

  CellPathResolver getCellPathResolver();

  Cell withCanonicalName(String canonicalName);

  Cell withCanonicalName(Optional<String> canonicalName);
}
