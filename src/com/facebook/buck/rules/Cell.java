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

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Config;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Cell {

  private final LoadingCache<String, Cell> cells;
  private final ProjectFilesystem filesystem;
  private final Watchman watchman;
  private final BuckConfig config;
  private final KnownBuildRuleTypes knownBuildRuleTypes;
  private final AndroidDirectoryResolver directoryResolver;
  private final String pythonInterpreter;
  private final String buildFileName;
  private final boolean enforceBuckPackageBoundaries;
  private final ImmutableSet<Pattern> tempFilePatterns;
  private CellFilesystemResolver cellFilesystemResolver;

  public Cell(
      final ProjectFilesystem filesystem,
      final Console console,
      final Watchman watchman,
      BuckConfig config,
      final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      final AndroidDirectoryResolver directoryResolver,
      final Clock clock) throws IOException, InterruptedException {

    this.filesystem = filesystem;
    this.watchman = watchman;
    this.config = config;
    this.directoryResolver = directoryResolver;

    ParserConfig parserConfig = new ParserConfig(config);
    this.buildFileName = parserConfig.getBuildFileName();
    this.enforceBuckPackageBoundaries = parserConfig.getEnforceBuckPackageBoundary();
    this.tempFilePatterns = parserConfig.getTempFilePatterns();

    PythonBuckConfig pythonConfig = new PythonBuckConfig(config, new ExecutableFinder());
    this.pythonInterpreter = pythonConfig.getPythonInterpreter();

    this.knownBuildRuleTypes = knownBuildRuleTypesFactory.create(config);

    this.cells = CacheBuilder.newBuilder().build(
        new CacheLoader<String, Cell>() {
          @Override
          public Cell load(String cellName) throws Exception {
            Optional<Path> maybeRoot = getBuckConfig().getPath("repositories", cellName, false);
            if (!maybeRoot.isPresent()) {
              throw new HumanReadableException(
                  "Unable to find repository named '%s' in repo rooted at %s",
                  cellName,
                  getFilesystem().getRootPath());
            }

            // Added the precondition check, though the resolve call can never return null.
            // TODO(user): Remove precondition check when possible.
            Path root = Preconditions.checkNotNull(
                getBuckConfig()
                    .resolvePathThatMayBeOutsideTheProjectFilesystem(maybeRoot.get()));

            // TODO(simons): Get the overrides from the parent config
            ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of();
            Config config = Config.createDefaultConfig(
                root,
                sections);

            ProjectFilesystem cellFilesystem = new ProjectFilesystem(root, config);

            Cell parent = Cell.this;
            BuckConfig parentConfig = parent.getBuckConfig();

            BuckConfig buckConfig = new BuckConfig(
                config,
                cellFilesystem,
                parentConfig.getPlatform(),
                parentConfig.getEnvironment());

            Watchman.build(root, parentConfig.getEnvironment(), console, clock);

            return new Cell(
                cellFilesystem,
                console,
                watchman,
                buckConfig,
                knownBuildRuleTypesFactory,
                directoryResolver,
                clock);
          }
        }
    );

    Function<Optional<String>, ProjectFilesystem> cellFilesystemAliases =
        new Function<Optional<String>, ProjectFilesystem>() {
          @Override
          public ProjectFilesystem apply(Optional<String> cellName) {
            return getCell(cellName).getFilesystem();
          }
        };
    this.cellFilesystemResolver =
        new CellFilesystemResolver(getFilesystem(), cellFilesystemAliases);
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return knownBuildRuleTypes;
  }

  public BuckConfig getBuckConfig() {
    return config;
  }

  public String getBuildFileName() {
    return buildFileName;
  }

  public boolean isEnforcingBuckPackageBoundaries() {
    return enforceBuckPackageBoundaries;
  }

  public Cell getCell(Optional<String> cellName) {
    if (!cellName.isPresent()) {
      return this;
    }

    try {
      return cells.getUnchecked(cellName.get());
    } catch (UncheckedExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof HumanReadableException) {
        throw (HumanReadableException) cause;
      }
      throw e;
    }
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

  public Path getAbsolutePathToBuildFile(BuildTarget target)
      throws MissingBuildFileException {
    Cell targetCell = getCell(target.getCell());

    ProjectFilesystem targetFilesystem = targetCell.getFilesystem();

    Path buildFile = targetFilesystem.resolve(target.getBasePath()).resolve(
        new ParserConfig(targetCell.getBuckConfig()).getBuildFileName());

    if (!targetFilesystem.isFile(buildFile)) {
      throw new MissingBuildFileException(target, targetCell.getBuckConfig());
    }
    return buildFile;
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public ProjectBuildFileParser createBuildFileParser(
      Console console,
      BuckEventBus eventBus,
      boolean useWatchmanGlob) {
    ProjectBuildFileParserFactory factory = createBuildFileParserFactory(useWatchmanGlob);
    return factory.createParser(console, config.getEnvironment(), eventBus);
  }

  @VisibleForTesting
  protected ProjectBuildFileParserFactory createBuildFileParserFactory(boolean useWatchmanGlob) {
    ParserConfig parserConfig = new ParserConfig(getBuckConfig());

    return new DefaultProjectBuildFileParserFactory(
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(getFilesystem().getRootPath())
            .setPythonInterpreter(pythonInterpreter)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setBuildFileName(getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(getAllDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .build());
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Cell that = (Cell) o;
    return Objects.equals(filesystem, that.filesystem) &&
        Objects.equals(config, that.config) &&
        Objects.equals(directoryResolver, that.directoryResolver);
  }

  @Override
  public int hashCode() {
    return Objects.hash(filesystem, config, directoryResolver);
  }

  public Iterable<Pattern> getTempFilePatterns() {
    return tempFilePatterns;
  }

  public CellFilesystemResolver getCellAliases() {
    return cellFilesystemResolver;
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {
    public MissingBuildFileException(BuildTarget buildTarget, BuckConfig buckConfig) {
      super(String.format("No build file at %s when resolving target %s.",
          buildTarget.getBasePathWithSlash() + new ParserConfig(buckConfig).getBuildFileName(),
          buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
