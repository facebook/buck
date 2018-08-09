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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
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
  /** Whether or not the cell has changed significantly enough to invalidate caches */
  public enum IsCompatibleForCaching {
    IS_COMPATIBLE,
    FILESYSTEM_CHANGED,
    BUCK_CONFIG_CHANGED,
    TOOLCHAINS_INCOMPATIBLE;

    /**
     * Returns a human readable reason for why the cache needs invalidated (or "" if the cache does
     * not need invalidated)
     */
    public String toHumanReasonableError() {
      switch (this) {
        case IS_COMPATIBLE:
          return "";
        case FILESYSTEM_CHANGED:
          return "The project directory changed between invocations";
        case BUCK_CONFIG_CHANGED:
          return "Buck configuration options changed between invocations";
        case TOOLCHAINS_INCOMPATIBLE:
          return "Available / configured toolchains changed between invocations";
      }
      return "";
    }
  }

  ImmutableSortedSet<Path> getKnownRoots();

  Optional<String> getCanonicalName();

  ProjectFilesystem getFilesystem();

  Watchman getWatchman();

  BuckConfig getBuckConfig();

  CellProvider getCellProvider();

  ToolchainProvider getToolchainProvider();

  Path getRoot();

  RuleKeyConfiguration getRuleKeyConfiguration();

  IsCompatibleForCaching isCompatibleForCaching(Cell other);

  String getBuildFileName();

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   */
  boolean isEnforcingBuckPackageBoundaries(Path path);

  Cell getCellIgnoringVisibilityCheck(Path cellPath);

  Cell getCell(Path cellPath);

  Cell getCell(BuildTarget target);

  Optional<Cell> getCellIfKnown(BuildTarget target);

  /**
   * Returns a list of all cells, including this cell. If this cell is the root, getAllCells will
   * necessarily return all possible cells that this build may interact with, since the root cell is
   * required to declare a mapping for all cell names.
   */
  ImmutableList<Cell> getAllCells();

  /** @return all loaded {@link Cell}s that are children of this {@link Cell}. */
  ImmutableMap<Path, Cell> getLoadedCells();

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getAbsolutePathToBuildFile(BuildTarget)}.
   *
   * @param target target to look up
   * @return path which may or may not exist.
   */
  Path getAbsolutePathToBuildFileUnsafe(BuildTarget target);

  Path getAbsolutePathToBuildFile(BuildTarget target) throws MissingBuildFileException;

  CellPathResolver getCellPathResolver();

  Cell withCanonicalName(String canonicalName);

  Cell withCanonicalName(Optional<String> canonicalName);
}
