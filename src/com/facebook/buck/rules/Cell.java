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
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Cell {

  private final ImmutableSet<Path> knownRoots;
  private final Cache<Path, Cell> cells;
  private final ProjectFilesystem filesystem;
  private final Watchman watchman;
  private final BuckConfig config;
  private final KnownBuildRuleTypes knownBuildRuleTypes;
  private final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory;
  private final AndroidDirectoryResolver directoryResolver;
  private final String pythonInterpreter;
  private final String buildFileName;
  private final boolean enforceBuckPackageBoundaries;
  private final ImmutableSet<Pattern> tempFilePatterns;
  private final CacheLoader<Path, Cell> cellLoader;

  private final Supplier<Integer> hashCodeSupplier = Suppliers.memoize(
      new Supplier<Integer>() {
        @Override
        public Integer get() {
          return Objects.hash(filesystem, config, directoryResolver);
        }
      });

  private Cell(
      final ImmutableSet<Path> knownRoots,
      final ProjectFilesystem filesystem,
      final Console console,
      final Watchman watchman,
      final BuckConfig config,
      final CellConstructionDelegate delegate,
      final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      final AndroidDirectoryResolver directoryResolver,
      final Clock clock,
      final Cache<Path, Cell> cells) throws IOException, InterruptedException {

    this.knownRoots = knownRoots;
    this.filesystem = filesystem;
    this.watchman = watchman;
    this.config = config;
    this.directoryResolver = directoryResolver;
    this.cells = cells;

    ParserConfig parserConfig = new ParserConfig(config);
    this.buildFileName = parserConfig.getBuildFileName();
    this.enforceBuckPackageBoundaries = parserConfig.getEnforceBuckPackageBoundary();
    this.tempFilePatterns = parserConfig.getTempFilePatterns();

    PythonBuckConfig pythonConfig = new PythonBuckConfig(config, new ExecutableFinder());
    this.pythonInterpreter = pythonConfig.getPythonInterpreter();

    this.knownBuildRuleTypesFactory = knownBuildRuleTypesFactory;
    this.knownBuildRuleTypes = knownBuildRuleTypesFactory.create(config);

    // The cache loader here uses a chunk of state from the to-be-minted cell. Creating this here
    // saves having to leak more of that state than needed.
    cellLoader = new CacheLoader<Path, Cell>() {
      @Override
      public Cell load(Path cellPath) throws Exception {
        cellPath = cellPath.toRealPath().normalize();

        if (!knownRoots.contains(cellPath)) {
          throw new HumanReadableException(
              "Unable to find repository rooted at %s. Known roots are:\n  %s",
              getFilesystem().getRootPath(),
              Joiner.on(",\n  ").join(knownRoots));
        }

        CellConstructionDelegateData constructionData = delegate.get(cellPath);
        Config config = constructionData.getConfig();

        ProjectFilesystem cellFilesystem = new ProjectFilesystem(cellPath, config);

        Cell parent = Cell.this;
        BuckConfig parentConfig = parent.getBuckConfig();
        BuckConfig buckConfig = new BuckConfig(
            config,
            cellFilesystem,
            parentConfig.getArchitecture(),
            parentConfig.getPlatform(),
            parentConfig.getEnvironment(),
            constructionData.getCellPathResolver());

        Watchman.build(cellPath, parentConfig.getEnvironment(), console, clock).close();

        return new Cell(
            constructionData.getKnownRoots(),
            cellFilesystem,
            console,
            watchman,
            buckConfig,
            delegate,
            knownBuildRuleTypesFactory,
            directoryResolver,
            clock,
            cells);
      }
    };

    // Ensure that the cell can find itself.
    cells.put(getFilesystem().getRootPath(), this);
  }

  public static Cell createCell(
      ProjectFilesystem filesystem,
      Console console,
      Watchman watchman,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      AndroidDirectoryResolver directoryResolver,
      Clock clock) throws IOException, InterruptedException {
    Cache<Path, Cell> cells = CacheBuilder.newBuilder().build();

    DefaultCellPathResolver rootCellCellPathResolver = new DefaultCellPathResolver(
        filesystem.getRootPath(),
        rootConfig.getEntriesForSection(DefaultCellPathResolver.REPOSITORIES_SECTION));

    final ImmutableMap<Path, RawConfig> pathToConfigOverrides = getPathToConfigOverrides(
        rootCellConfigOverrides,
        rootCellCellPathResolver.getTransitivePathMapping());

    CellConstructionDelegate delegate = new CellConstructionDelegate() {
      @Override
      public CellConstructionDelegateData get(Path cellPath) throws IOException {
        RawConfig configOverrides = Optional.fromNullable(pathToConfigOverrides.get(cellPath))
            .or(RawConfig.of(ImmutableMap.<String, ImmutableMap<String, String>>of()));
        Config config = Configs.createDefaultConfig(
            cellPath,
            configOverrides);
        DefaultCellPathResolver cellPathResolver =
            new DefaultCellPathResolver(cellPath, config);
        return CellConstructionDelegateData.builder()
            .setCellPathResolver(cellPathResolver)
            .setConfig(config)
            .setKnownRoots(cellPathResolver.getKnownRoots())
            .build();
      }
    };

    return new Cell(
        rootCellCellPathResolver.getKnownRoots(),
        filesystem,
        console,
        watchman,
        rootConfig,
        delegate,
        knownBuildRuleTypesFactory,
        directoryResolver,
        clock,
        cells);
  }

  private static ImmutableMap<Path, RawConfig> getPathToConfigOverrides(
      CellConfig rootCellConfigOverrides,
      ImmutableMultimap<Path, RelativeCellName> transitivePathMapping) {

    Map<Path, RawConfig.Builder> overridesByPath = new HashMap<>();
    for (Map.Entry<Path, RelativeCellName> entry : transitivePathMapping.entries()) {
      Path cellPath = entry.getKey();
      RawConfig.Builder builder = overridesByPath.get(cellPath);
      if (builder == null) {
        builder = RawConfig.builder();
        overridesByPath.put(cellPath, builder);
      }
      builder.putAll(rootCellConfigOverrides.getForCell(entry.getValue()));
    }

    return ImmutableMap.copyOf(
        Maps.transformValues(
            overridesByPath,
            new Function<RawConfig.Builder, RawConfig>() {
              @Override
              public RawConfig apply(RawConfig.Builder input) {
                return input.build();
              }
            }));
  }

  public Cell createCellForDistributedBuild(
      Console console,
      Clock clock,
      ProjectFilesystem filesystem,
      BuckConfig config,
      CellConstructionDelegate delegate) throws InterruptedException, IOException {

    return new Cell(
        delegate.get(filesystem.getRootPath()).getKnownRoots(),
        filesystem,
        console,
        watchman,
        config,
        delegate,
        knownBuildRuleTypesFactory,
        directoryResolver,
        clock,
        cells);
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Path getRoot() {
    return getFilesystem().getRootPath();
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

  public Cell getCell(Path path) {
    final Path cellPath = path;

    try {
      return cells.get(cellPath, new Callable<Cell>() {
        @Override
        public Cell call() throws Exception {
          return cellLoader.load(cellPath);
        }
      });
    } catch (ExecutionException | UncheckedExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), HumanReadableException.class);
      throw Throwables.propagate(e);
    }
  }

  public Cell getCell(BuildTarget target) {
    final Path cellPath = target.getCellPath();

    try {
      return cells.get(cellPath, new Callable<Cell>() {
        @Override
        public Cell call() throws Exception {
          return cellLoader.load(cellPath);
        }
      });
    } catch (ExecutionException | UncheckedExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), HumanReadableException.class);
      throw Throwables.propagate(e);
    }
  }

  public Optional<Cell> getCellIfKnown(BuildTarget target) {
    if (knownRoots.contains(target.getCellPath())) {
      return Optional.of(getCell(target));
    }
    return Optional.absent();
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
    Cell targetCell = getCell(target);

    ProjectFilesystem targetFilesystem = targetCell.getFilesystem();

    Path buildFile = targetFilesystem
        .resolve(target.getBasePath())
        .resolve(targetCell.getBuildFileName());

    if (!targetFilesystem.isFile(buildFile)) {
      throw new MissingBuildFileException(target, targetCell.getBuckConfig());
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
      ConstructorArgMarshaller marshaller,
      Console console,
      BuckEventBus eventBus,
      boolean ignoreBuckAutodepsFiles) {
    ParserConfig parserConfig = new ParserConfig(getBuckConfig());
    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN &&
        watchman.hasWildmatchGlob();
    ProjectBuildFileParserFactory factory = createBuildFileParserFactory(useWatchmanGlob);
    return factory.createParser(
        marshaller,
        console,
        config.getEnvironment(),
        eventBus,
        ignoreBuckAutodepsFiles);
  }

  @VisibleForTesting
  protected ProjectBuildFileParserFactory createBuildFileParserFactory(boolean useWatchmanGlob) {
    ParserConfig parserConfig = new ParserConfig(getBuckConfig());

    return new DefaultProjectBuildFileParserFactory(
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(getFilesystem().getRootPath())
            .setPythonInterpreter(pythonInterpreter)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(filesystem.getIgnorePaths())
            .setBuildFileName(getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(getAllDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(getBuckConfig().getRawConfigForParser())
            .setEnableBuildFileSandboxing(parserConfig.getEnableBuildFileSandboxing())
            .build());
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Cell that = (Cell) o;
    return Objects.equals(filesystem, that.filesystem) &&
        config.equalsForDaemonRestart(that.config) &&
        Objects.equals(directoryResolver, that.directoryResolver);
  }

  @Override
  public String toString() {
    return String.format(
        "%s filesystem=%s config=%s directoryResolver=%s",
        super.toString(),
        filesystem,
        config,
        directoryResolver);
  }

  @Override
  public int hashCode() {
    return hashCodeSupplier.get();
  }

  public Iterable<Pattern> getTempFilePatterns() {
    return tempFilePatterns;
  }

  public CellPathResolver getCellRoots() {
    return config.getCellRoots();
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

  public interface CellConstructionDelegate {
    CellConstructionDelegateData get(Path cellPath) throws IOException;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractCellConstructionDelegateData {
    Config getConfig();
    CellPathResolver getCellPathResolver();
    ImmutableSet<Path> getKnownRoots();
  }
}
