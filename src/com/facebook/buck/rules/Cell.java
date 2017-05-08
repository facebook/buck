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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Cell {

  private final ImmutableSet<Path> knownRoots;
  private final Optional<String> canonicalName;
  private final ProjectFilesystem filesystem;
  private final Watchman watchman;
  private final BuckConfig config;
  private final CellProvider cellProvider;
  private final Supplier<KnownBuildRuleTypes> knownBuildRuleTypesSupplier;

  private final int hashCode;

  /** Should only be constructed by {@link CellProvider}. */
  Cell(
      ImmutableSet<Path> knownRoots,
      Optional<String> canonicalName,
      ProjectFilesystem filesystem,
      Watchman watchman,
      BuckConfig config,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      CellProvider cellProvider) {

    this.knownRoots = knownRoots;
    this.canonicalName = canonicalName;
    this.filesystem = filesystem;
    this.watchman = watchman;
    this.config = config;
    this.cellProvider = cellProvider;

    // Stampede needs the Cell before it can materialize all the files required by
    // knownBuildRuleTypesFactory (specifically java/javac), and as such we need to load this
    // lazily when getKnownBuildRuleTypes() is called.
    knownBuildRuleTypesSupplier =
        Suppliers.memoize(
            () -> {
              try {
                return knownBuildRuleTypesFactory.create(config, filesystem);
              } catch (IOException e) {
                throw new RuntimeException(
                    String.format(
                        "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                        filesystem.getRootPath()),
                    e);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                    String.format(
                        "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                        filesystem.getRootPath()),
                    e);
              }
            });

    hashCode = Objects.hash(filesystem, config);
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Path getRoot() {
    return getFilesystem().getRootPath();
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return knownBuildRuleTypesSupplier.get();
  }

  public BuckConfig getBuckConfig() {
    return config;
  }

  public String getBuildFileName() {
    return config.getView(ParserConfig.class).getBuildFileName();
  }

  public Optional<String> getCanonicalName() {
    return canonicalName;
  }

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   */
  public boolean isEnforcingBuckPackageBoundaries(Path path) {
    ParserConfig configView = config.getView(ParserConfig.class);
    if (!configView.getEnforceBuckPackageBoundary()) {
      return false;
    }

    Path absolutePath = filesystem.resolve(path);

    ImmutableList<Path> exceptions = configView.getBuckPackageBoundaryExceptions();
    for (Path exception : exceptions) {
      if (absolutePath.startsWith(exception)) {
        return false;
      }
    }
    return true;
  }

  public Cell getCellIgnoringVisibilityCheck(Path cellPath) {
    return cellProvider.getCellByPath(cellPath);
  }

  public Cell getCell(Path cellPath) {
    if (!knownRoots.contains(cellPath)) {
      throw new HumanReadableException(
          "Unable to find repository rooted at %s. Known roots are:\n  %s",
          cellPath, Joiner.on(",\n  ").join(knownRoots));
    }
    return getCellIgnoringVisibilityCheck(cellPath);
  }

  public Cell getCell(BuildTarget target) {
    return getCell(target.getCellPath());
  }

  public Optional<Cell> getCellIfKnown(BuildTarget target) {
    if (knownRoots.contains(target.getCellPath())) {
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
    return RichStream.from(knownRoots)
        .concat(RichStream.of(getRoot()))
        .distinct()
        .map(cellProvider::getCellByPath)
        .toImmutableList();
  }

  /** @return all loaded {@link Cell}s that are children of this {@link Cell}. */
  public ImmutableMap<Path, Cell> getLoadedCells() {
    return cellProvider.getLoadedCells();
  }

  public Description<?> getDescription(BuildRuleType type) {
    return getKnownBuildRuleTypes().getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return getKnownBuildRuleTypes().getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return getKnownBuildRuleTypes().getAllDescriptions();
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

    Path buildFile =
        targetFilesystem.resolve(target.getBasePath()).resolve(targetCell.getBuildFileName());
    return buildFile;
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target) throws MissingBuildFileException {
    Path buildFile = getAbsolutePathToBuildFileUnsafe(target);
    Cell cell = getCell(target);
    if (!cell.getFilesystem().isFile(buildFile)) {
      throw new MissingBuildFileException(target, cell.getBuckConfig());
    }
    return buildFile;
  }

  public Watchman getWatchman() {
    return watchman;
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public ProjectBuildFileParser createBuildFileParser(
      TypeCoercerFactory typeCoercerFactory, Console console, BuckEventBus eventBus) {

    ParserConfig parserConfig = getBuckConfig().getView(ParserConfig.class);

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN
            && watchman.hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator =
        watchman.getCapabilities().contains(Watchman.Capability.GLOB_GENERATOR);
    boolean useMercurialGlob = parserConfig.getGlobHandler() == ParserConfig.GlobHandler.MERCURIAL;
    String pythonInterpreter = parserConfig.getPythonInterpreter(new ExecutableFinder());
    Optional<String> pythonModuleSearchPath = parserConfig.getPythonModuleSearchPath();

    return new ProjectBuildFileParser(
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(getFilesystem().getRootPath())
            .setCellRoots(getCellPathResolver().getCellPaths())
            .setCellName(getCanonicalName().orElse(""))
            .setPythonInterpreter(pythonInterpreter)
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(filesystem.getIgnorePaths())
            .setBuildFileName(getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(getAllDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setUseMercurialGlob(useMercurialGlob)
            .setRawConfig(getBuckConfig().getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .build(),
        typeCoercerFactory,
        config.getEnvironment(),
        eventBus,
        new DefaultProcessExecutor(console));
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Cell that = (Cell) o;
    return Objects.equals(filesystem, that.filesystem)
        && config.equalsForDaemonRestart(that.config);
  }

  @Override
  public String toString() {
    return String.format("%s filesystem=%s config=%s", super.toString(), filesystem, config);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  public CellPathResolver getCellPathResolver() {
    return config.getCellPathResolver();
  }

  public ImmutableSet<Path> getKnownRoots() {
    return knownRoots;
  }

  public void ensureConcreteFilesExist(BuckEventBus eventBus) {
    filesystem.ensureConcreteFilesExist(eventBus);
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {
    public MissingBuildFileException(BuildTarget buildTarget, BuckConfig buckConfig) {
      super(
          String.format(
              "No build file at %s when resolving target %s.",
              buildTarget
                  .getBasePath()
                  .resolve(buckConfig.getView(ParserConfig.class).getBuildFileName()),
              buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
