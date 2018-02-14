/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PerBuildState implements AutoCloseable {
  private static final Logger LOG = Logger.get(PerBuildState.class);

  private final Parser parser;
  private final AtomicLong parseProcessedBytes = new AtomicLong();
  private final BuckEventBus eventBus;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final boolean enableProfiling;

  private final Console console;

  private final Map<Path, Cell> cells;
  private final Map<Path, ParserConfig.AllowSymlinks> cellSymlinkAllowability;
  /**
   * Build rule input files (e.g., paths in {@code srcs}) whose paths contain an element which
   * exists in {@code symlinkExistenceCache}.
   */
  private final Set<Path> buildInputPathsUnderSymlink;

  /**
   * Cache of (symlink path: symlink target) pairs used to avoid repeatedly checking for the
   * existence of symlinks in the source tree.
   */
  private final Map<Path, Optional<Path>> symlinkExistenceCache;

  private final ProjectBuildFileParserPool projectBuildFileParserPool;
  private final RawNodeParsePipeline rawNodeParsePipeline;
  private final TargetNodeParsePipeline targetNodeParsePipeline;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;

  public enum SpeculativeParsing {
    ENABLED,
    DISABLED,
  }

  public PerBuildState(
      Parser parser,
      BuckEventBus eventBus,
      ExecutableFinder executableFinder,
      ListeningExecutorService executorService,
      Cell rootCell,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing) {
    this(
        parser,
        eventBus,
        new ParserPythonInterpreterProvider(rootCell.getBuckConfig(), executableFinder),
        executorService,
        rootCell,
        knownBuildRuleTypesProvider,
        enableProfiling,
        speculativeParsing);
  }

  PerBuildState(
      Parser parser,
      BuckEventBus eventBus,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      ListeningExecutorService executorService,
      Cell rootCell,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing) {

    this.parser = parser;
    this.eventBus = eventBus;
    this.parserPythonInterpreterProvider = parserPythonInterpreterProvider;
    this.enableProfiling = enableProfiling;
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;

    this.cells = new ConcurrentHashMap<>();
    this.cellSymlinkAllowability = new ConcurrentHashMap<>();
    this.buildInputPathsUnderSymlink = Sets.newConcurrentHashSet();
    this.symlinkExistenceCache = new ConcurrentHashMap<>();

    this.console = Console.createNullConsole();

    TargetNodeListener<TargetNode<?, ?>> symlinkCheckers = this::registerInputsUnderSymlinks;
    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    int numParsingThreads = parserConfig.getNumParsingThreads();
    this.projectBuildFileParserPool =
        new ProjectBuildFileParserPool(
            numParsingThreads, // Max parsers to create per cell.
            cell ->
                createBuildFileParser(
                    cell, knownBuildRuleTypesProvider.get(cell).getDescriptions()),
            enableProfiling);

    this.rawNodeParsePipeline =
        new RawNodeParsePipeline(
            parser.getPermState().getRawNodeCache(), projectBuildFileParserPool, executorService);
    this.targetNodeParsePipeline =
        new TargetNodeParsePipeline(
            parser.getPermState().getOrCreateNodeCache(TargetNode.class),
            DefaultParserTargetNodeFactory.createForParser(
                parser.getMarshaller(),
                parser.getPermState().getBuildFileTrees(),
                symlinkCheckers,
                new TargetNodeFactory(parser.getPermState().getTypeCoercerFactory()),
                rootCell.getRuleKeyConfiguration()),
            parserConfig.getEnableParallelParsing()
                ? executorService
                : MoreExecutors.newDirectExecutorService(),
            eventBus,
            parserConfig.getEnableParallelParsing()
                && speculativeParsing == SpeculativeParsing.ENABLED,
            rawNodeParsePipeline,
            knownBuildRuleTypesProvider);

    register(rootCell);
  }

  public TargetNode<?, ?> getTargetNode(BuildTarget target)
      throws BuildFileParseException, BuildTargetException {
    Cell owningCell = getCell(target);

    return targetNodeParsePipeline.getNode(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  public ListenableFuture<TargetNode<?, ?>> getTargetNodeJob(BuildTarget target)
      throws BuildTargetException {
    Cell owningCell = getCell(target);

    return targetNodeParsePipeline.getNodeJob(
        owningCell, knownBuildRuleTypesProvider.get(owningCell), target, parseProcessedBytes);
  }

  public ImmutableSet<TargetNode<?, ?>> getAllTargetNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  public ListenableFuture<ImmutableSet<TargetNode<?, ?>>> getAllTargetNodesJob(
      Cell cell, Path buildFile) throws BuildTargetException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllNodesJob(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  public ImmutableSet<Map<String, Object>> getAllRawNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    // The raw nodes are just plain JSON blobs, and so we don't need to check for symlinks
    return rawNodeParsePipeline.getAllNodes(
        cell, knownBuildRuleTypesProvider.get(cell), buildFile, parseProcessedBytes);
  }

  private ProjectBuildFileParser createBuildFileParser(
      Cell cell, Iterable<Description<?>> descriptions) {
    return ProjectBuildFileParserFactory.createBuildFileParser(
        cell,
        this.parser.getTypeCoercerFactory(),
        console,
        eventBus,
        parserPythonInterpreterProvider,
        descriptions,
        enableProfiling);
  }

  private void register(Cell cell) {
    Path root = cell.getFilesystem().getRootPath();
    if (!cells.containsKey(root)) {
      cells.put(root, cell);
      cellSymlinkAllowability.put(
          root, cell.getBuckConfig().getView(ParserConfig.class).getAllowSymlinks());
    }
  }

  private Cell getCell(BuildTarget target) {
    Cell cell = cells.get(target.getCellPath());
    if (cell != null) {
      return cell;
    }

    for (Cell possibleOwner : cells.values()) {
      Optional<Cell> maybe = possibleOwner.getCellIfKnown(target);
      if (maybe.isPresent()) {
        register(maybe.get());
        return maybe.get();
      }
    }
    throw new HumanReadableException(
        "From %s, unable to find cell rooted at: %s", target, target.getCellPath());
  }

  private void registerInputsUnderSymlinks(Path buildFile, TargetNode<?, ?> node)
      throws IOException {
    Map<Path, Path> newSymlinksEncountered =
        inputFilesUnderSymlink(node.getInputs(), node.getFilesystem(), symlinkExistenceCache);
    Optional<ImmutableList<Path>> readOnlyPaths =
        getCell(node.getBuildTarget())
            .getBuckConfig()
            .getView(ParserConfig.class)
            .getReadOnlyPaths();
    Cell currentCell = cells.get(node.getBuildTarget().getCellPath());

    if (readOnlyPaths.isPresent() && currentCell != null) {
      newSymlinksEncountered =
          Maps.filterEntries(
              newSymlinksEncountered,
              entry -> {
                for (Path readOnlyPath : readOnlyPaths.get()) {
                  if (entry.getKey().startsWith(readOnlyPath)) {
                    LOG.debug(
                        "Target %s contains input files under a path which contains a symbolic "
                            + "link (%s). It will be cached because it belongs under %s, a "
                            + "read-only path white listed in .buckconfig. under [project] "
                            + "read_only_paths",
                        node.getBuildTarget(), entry, readOnlyPath);
                    return false;
                  }
                }
                return true;
              });
    }

    if (newSymlinksEncountered.isEmpty()) {
      return;
    }

    ParserConfig.AllowSymlinks allowSymlinks =
        Preconditions.checkNotNull(
            cellSymlinkAllowability.get(node.getBuildTarget().getCellPath()));
    if (allowSymlinks == ParserConfig.AllowSymlinks.FORBID) {
      throw new HumanReadableException(
          "Target %s contains input files under a path which contains a symbolic link "
              + "(%s). To resolve this, use separate rules and declare dependencies instead of "
              + "using symbolic links.\n"
              + "If the symlink points to a read-only filesystem, you can specify it in the "
              + "project.read_only_paths .buckconfig setting. Buck will assume files under that "
              + "path will never change.",
          node.getBuildTarget(), newSymlinksEncountered);
    }

    // If we're not explicitly forbidding symlinks, either warn to the console or the log file
    // depending on the config setting.
    String msg =
        String.format(
            "Disabling parser cache for target %s, because one or more input files are under a "
                + "symbolic link (%s). This will severely impact the time spent in parsing! To "
                + "resolve this, use separate rules and declare dependencies instead of using "
                + "symbolic links.",
            node.getBuildTarget(), newSymlinksEncountered);
    if (allowSymlinks == ParserConfig.AllowSymlinks.WARN) {
      eventBus.post(ConsoleEvent.warning(msg));
    } else {
      LOG.warn(msg);
    }

    eventBus.post(ParsingEvent.symlinkInvalidation(buildFile.toString()));
    buildInputPathsUnderSymlink.add(buildFile);
  }

  private static Map<Path, Path> inputFilesUnderSymlink(
      // We use Collection<Path> instead of Iterable<Path> to prevent
      // accidentally passing in Path, since Path itself is Iterable<Path>.
      Collection<Path> inputs,
      ProjectFilesystem projectFilesystem,
      Map<Path, Optional<Path>> symlinkExistenceCache)
      throws IOException {
    Map<Path, Path> newSymlinksEncountered = new HashMap<>();
    for (Path input : inputs) {
      for (int i = 1; i < input.getNameCount(); i++) {
        Path subpath = input.subpath(0, i);
        Optional<Path> resolvedSymlink = symlinkExistenceCache.get(subpath);
        if (resolvedSymlink != null) {
          if (resolvedSymlink.isPresent()) {
            LOG.verbose("Detected cached symlink %s -> %s", subpath, resolvedSymlink.get());
            newSymlinksEncountered.put(subpath, resolvedSymlink.get());
          }
          // If absent, not a symlink.
        } else {
          // Not cached, look it up.
          if (projectFilesystem.isSymLink(subpath)) {
            Path symlinkTarget = projectFilesystem.resolve(subpath).toRealPath();
            Path relativeSymlinkTarget =
                projectFilesystem.getPathRelativeToProjectRoot(symlinkTarget).orElse(symlinkTarget);
            LOG.verbose("Detected symbolic link %s -> %s", subpath, relativeSymlinkTarget);
            newSymlinksEncountered.put(subpath, relativeSymlinkTarget);
            symlinkExistenceCache.put(subpath, Optional.of(relativeSymlinkTarget));
          } else {
            symlinkExistenceCache.put(subpath, Optional.empty());
          }
        }
      }
    }
    return newSymlinksEncountered;
  }

  public void ensureConcreteFilesExist(BuckEventBus eventBus) {
    for (Cell eachCell : cells.values()) {
      eachCell.ensureConcreteFilesExist(eventBus);
    }
  }

  public long getParseProcessedBytes() {
    return parseProcessedBytes.get();
  }

  @Override
  public void close() throws BuildFileParseException {
    targetNodeParsePipeline.close();
    rawNodeParsePipeline.close();
    projectBuildFileParserPool.close();

    if (!buildInputPathsUnderSymlink.isEmpty()) {
      LOG.debug(
          "Cleaning cache of build files with inputs under symlink %s",
          buildInputPathsUnderSymlink);
    }
    Set<Path> buildInputPathsUnderSymlinkCopy = new HashSet<>(buildInputPathsUnderSymlink);
    buildInputPathsUnderSymlink.clear();
    for (Path buildFilePath : buildInputPathsUnderSymlinkCopy) {
      parser.getPermState().invalidatePath(buildFilePath);
    }
  }
}
