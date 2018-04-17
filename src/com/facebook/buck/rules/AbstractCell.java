/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.toolchain.ComparableToolchain;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 *
 * <p>Should only be constructed by {@link CellProvider}.
 */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractCell {

  @Value.Auxiliary
  abstract ImmutableSet<Path> getKnownRoots();

  @Value.Auxiliary
  abstract Optional<String> getCanonicalName();

  abstract ProjectFilesystem getFilesystem();

  @Value.Auxiliary
  abstract Watchman getWatchman();

  abstract BuckConfig getBuckConfig();

  @Value.Auxiliary
  abstract CellProvider getCellProvider();

  @Value.Auxiliary
  abstract ToolchainProvider getToolchainProvider();

  public Path getRoot() {
    return getFilesystem().getRootPath();
  }

  @Value.Auxiliary
  abstract RuleKeyConfiguration getRuleKeyConfiguration();

  public boolean isCompatibleForCaching(Cell other) {
    return (getFilesystem().equals(other.getFilesystem())
        && getBuckConfig().equalsForDaemonRestart(other.getBuckConfig())
        && areToolchainsCompatibleForCaching(other));
  }

  private boolean areToolchainsCompatibleForCaching(Cell other) {
    ToolchainProvider toolchainProvider = getToolchainProvider();
    ToolchainProvider otherToolchainProvider = other.getToolchainProvider();

    Set<String> toolchains = new HashSet<>();
    toolchains.addAll(toolchainProvider.getToolchainsWithCapability(ComparableToolchain.class));
    toolchains.addAll(
        otherToolchainProvider.getToolchainsWithCapability(ComparableToolchain.class));

    for (String toolchain : toolchains) {
      if (!toolchainsStateEqual(toolchain, toolchainProvider, otherToolchainProvider)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Checks the state of two toolchains is compatible.
   *
   * <p>When comparing two toolchains:
   *
   * <ol>
   *   <li>if both were not created nor failed then return true
   *   <li>if one of the toolchains is failed then true only if second toolchain has the same
   *       exception
   *   <li>ask for presence and:
   *       <ul>
   *         <li>if both are not present then true
   *         <li>if both are present compare them
   *         <li>if one is not present then false
   *       </ul>
   * </ol>
   */
  private boolean toolchainsStateEqual(
      String toolchain,
      ToolchainProvider toolchainProvider,
      ToolchainProvider otherToolchainProvider) {

    boolean toolchainFailed = toolchainProvider.isToolchainFailed(toolchain);
    boolean otherToolchainFailed = otherToolchainProvider.isToolchainFailed(toolchain);
    boolean toolchainCreated = toolchainProvider.isToolchainCreated(toolchain);
    boolean otherToolchainCreated = otherToolchainProvider.isToolchainCreated(toolchain);

    boolean toolchainInstantiated = toolchainFailed || toolchainCreated;
    boolean otherToolchainInstantiated = otherToolchainFailed || otherToolchainCreated;

    if (!toolchainInstantiated && !otherToolchainInstantiated) {
      return true;
    }

    if (toolchainFailed || otherToolchainFailed) {
      Optional<ToolchainInstantiationException> exception =
          getFailedToolchainException(toolchainProvider, toolchain);
      Optional<ToolchainInstantiationException> otherException =
          getFailedToolchainException(otherToolchainProvider, toolchain);

      return exception.isPresent()
          && otherException.isPresent()
          && exception
              .get()
              .getHumanReadableErrorMessage()
              .equals(otherException.get().getHumanReadableErrorMessage());
    }

    boolean toolchainPresent = toolchainProvider.isToolchainPresent(toolchain);
    boolean otherToolchainPresent = otherToolchainProvider.isToolchainPresent(toolchain);

    // Both toolchains exist, compare them
    if (toolchainPresent && otherToolchainPresent) {
      return toolchainProvider
          .getByName(toolchain)
          .equals(otherToolchainProvider.getByName(toolchain));
    } else {
      return !toolchainPresent && !otherToolchainPresent;
    }
  }

  private Optional<ToolchainInstantiationException> getFailedToolchainException(
      ToolchainProvider toolchainProvider, String toolchainName) {
    if (toolchainProvider.isToolchainPresent(toolchainName)) {
      return Optional.empty();
    } else {
      return toolchainProvider.getToolchainInstantiationException(toolchainName);
    }
  }

  public String getBuildFileName() {
    return getBuckConfig().getView(ParserConfig.class).getBuildFileName();
  }

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   */
  public boolean isEnforcingBuckPackageBoundaries(Path path) {
    ParserConfig configView = getBuckConfig().getView(ParserConfig.class);
    if (!configView.getEnforceBuckPackageBoundary()) {
      return false;
    }

    Path absolutePath = getFilesystem().resolve(path);

    ImmutableList<Path> exceptions = configView.getBuckPackageBoundaryExceptions();
    for (Path exception : exceptions) {
      if (absolutePath.startsWith(exception)) {
        return false;
      }
    }
    return true;
  }

  public Cell getCellIgnoringVisibilityCheck(Path cellPath) {
    return getCellProvider().getCellByPath(cellPath);
  }

  public Cell getCell(Path cellPath) {
    if (!getKnownRoots().contains(cellPath)) {
      throw new HumanReadableException(
          "Unable to find repository rooted at %s. Known roots are:\n  %s",
          cellPath, Joiner.on(",\n  ").join(getKnownRoots()));
    }
    return getCellIgnoringVisibilityCheck(cellPath);
  }

  public Cell getCell(BuildTarget target) {
    return getCell(target.getCellPath());
  }

  public Optional<Cell> getCellIfKnown(BuildTarget target) {
    if (getKnownRoots().contains(target.getCellPath())) {
      return Optional.of(getCell(target));
    }
    return Optional.empty();
  }

  /**
   * Returns a list of all cells, including this cell. If this cell is the root, getAllCells will
   * necessarily return all possible cells that this build may interact with, since the root cell is
   * required to declare a mapping for all cell names.
   */
  public ImmutableList<Cell> getAllCells() {
    return RichStream.from(getKnownRoots())
        .concat(RichStream.of(getRoot()))
        .distinct()
        .map(getCellProvider()::getCellByPath)
        .toImmutableList();
  }

  /** @return all loaded {@link Cell}s that are children of this {@link Cell}. */
  public ImmutableMap<Path, Cell> getLoadedCells() {
    return getCellProvider().getLoadedCells();
  }

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getAbsolutePathToBuildFile(BuildTarget)}.
   *
   * @param target target to look up
   * @return path which may or may not exist.
   */
  public Path getAbsolutePathToBuildFileUnsafe(BuildTarget target) {
    Cell targetCell = getCell(target);
    ProjectFilesystem targetFilesystem = targetCell.getFilesystem();
    return targetFilesystem.resolve(target.getBasePath()).resolve(targetCell.getBuildFileName());
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target) throws MissingBuildFileException {
    Path buildFile = getAbsolutePathToBuildFileUnsafe(target);
    Cell cell = getCell(target);
    if (!cell.getFilesystem().isFile(buildFile)) {

      throw new MissingBuildFileException(
          target.getFullyQualifiedName(),
          target
              .getBasePath()
              .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName()));
    }
    return buildFile;
  }

  public CellPathResolver getCellPathResolver() {
    return getBuckConfig().getCellPathResolver();
  }
}
