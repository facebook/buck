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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.immutables.value.Value;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class PerBuildState implements AutoCloseable {
  private static final Logger LOG = Logger.get(PerBuildState.class);

  private final DaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;
  private final BuckEventBus eventBus;
  private final boolean enableProfiling;
  private final boolean ignoreBuckAutodepsFiles;

  private final PrintStream stdout;
  private final PrintStream stderr;
  private final Console console;

  private final Map<Path, Cell> cells;
  private final Map<Path, ParserConfig.AllowSymlinks> cellSymlinkAllowability;
  /**
   * Build rule input files (e.g., paths in {@code srcs}) whose
   * paths contain an element which exists in {@code symlinkExistenceCache}.
   */
  private final Set<Path> buildInputPathsUnderSymlink;
  private final TargetNodeListener symlinkCheckers;

  /**
   * Cache of (symlink path: symlink target) pairs used to avoid repeatedly
   * checking for the existence of symlinks in the source tree.
   */
  private final Map<Path, Optional<Path>> symlinkExistenceCache;

  private ProjectBuildFileParserPool projectBuildFileParserPool;
  private ParsePipeline parsePipeline;

  public PerBuildState(
      DaemonicParserState permState,
      ConstructorArgMarshaller marshaller,
      BuckEventBus eventBus,
      ListeningExecutorService executorService,
      Cell rootCell,
      boolean enableProfiling,
      SpeculativeParsing speculativeParsing,
      boolean ignoreBuckAutodepsFiles) {
    this.permState = permState;
    this.marshaller = marshaller;
    this.eventBus = eventBus;
    this.enableProfiling = enableProfiling;
    this.ignoreBuckAutodepsFiles = ignoreBuckAutodepsFiles;

    this.cells = new ConcurrentHashMap<>();
    this.cellSymlinkAllowability = new ConcurrentHashMap<>();
    this.buildInputPathsUnderSymlink = Sets.newConcurrentHashSet();
    this.symlinkExistenceCache = new ConcurrentHashMap<>();

    this.stdout = new PrintStream(ByteStreams.nullOutputStream());
    this.stderr = new PrintStream(ByteStreams.nullOutputStream());
    this.console = new Console(Verbosity.STANDARD_INFORMATION, stdout, stderr, Ansi.withoutTty());

    this.symlinkCheckers = new TargetNodeListener() {
      @Override
      public void onCreate(Path buildFile, TargetNode<?> node) throws IOException {
        registerInputsUnderSymlinks(buildFile, node);
      }
    };
    ParserConfig parserConfig = new ParserConfig(rootCell.getBuckConfig());
    int numParsingThreads = parserConfig.getNumParsingThreads();
    this.projectBuildFileParserPool = new ProjectBuildFileParserPool(
        numParsingThreads, // Max parsers to create per cell.
        new Function<Cell, ProjectBuildFileParser>() {
          @Override
          public ProjectBuildFileParser apply(Cell input) {
            return createBuildFileParser(input, PerBuildState.this.ignoreBuckAutodepsFiles);
          }
        });
    this.parsePipeline = new ParsePipeline(
        permState,
        DefaultParserTargetNodeFactory.createForParser(
            eventBus,
            marshaller,
            permState.getTypeCoercerFactory(),
            permState.getBuildFileTrees(),
            symlinkCheckers),
        parserConfig.getEnableParallelParsing() ?
            executorService :
            MoreExecutors.newDirectExecutorService(),
        eventBus,
        projectBuildFileParserPool,
        parserConfig.getEnableParallelParsing() && speculativeParsing.value()
    );

    register(rootCell);
  }

  public TargetNode<?> getTargetNode(BuildTarget target)
      throws BuildFileParseException, BuildTargetException {
    Cell owningCell = getCell(target);

    return parsePipeline.getTargetNode(owningCell, target);
  }

  public ImmutableSet<TargetNode<?>> getAllTargetNodes(Cell cell, Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return parsePipeline.getAllTargetNodes(cell, buildFile);
  }

  public ListenableFuture<ImmutableSet<TargetNode<?>>> getAllTargetNodesJob(
      Cell cell,
      Path buildFile) {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return parsePipeline.getAllTargetNodesJob(cell, buildFile);
  }

  public ImmutableList<Map<String, Object>> getAllRawNodes(Cell cell, Path buildFile)
      throws InterruptedException, BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    // The raw nodes are just plain JSON blobs, and so we don't need to check for symlinks
    return parsePipeline.getRawNodes(cell, buildFile);
  }

  private ProjectBuildFileParser createBuildFileParser(Cell cell, boolean ignoreBuckAutodepsFiles) {
    ProjectBuildFileParser parser = cell.createBuildFileParser(
        marshaller,
        console,
        eventBus,
        ignoreBuckAutodepsFiles);
    parser.setEnableProfiling(enableProfiling);
    return parser;
  }

  private void register(Cell cell) {
    Path root = cell.getFilesystem().getRootPath();
    if (!cells.containsKey(root)) {
      cells.put(root, cell);
      cellSymlinkAllowability.put(root, new ParserConfig(cell.getBuckConfig()).getAllowSymlinks());
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
        "From %s, unable to find cell rooted at: %s",
        target,
        target.getCellPath());
  }

  private void registerInputsUnderSymlinks(
      Path buildFile,
      TargetNode<?> node) throws IOException {
    Map<Path, Path> newSymlinksEncountered =
        inputFilesUnderSymlink(
            node.getInputs(),
            node.getRuleFactoryParams().getProjectFilesystem(),
            symlinkExistenceCache);
    if (!newSymlinksEncountered.isEmpty()) {
      ParserConfig.AllowSymlinks allowSymlinks = Preconditions.checkNotNull(
          cellSymlinkAllowability.get(node.getBuildTarget().getCellPath()));
      if (allowSymlinks == ParserConfig.AllowSymlinks.FORBID) {
        throw new HumanReadableException(
            "Target %s contains input files under a path which contains a symbolic link " +
                "(%s). To resolve this, use separate rules and declare dependencies instead of " +
                "using symbolic links.",
            node.getBuildTarget(),
            newSymlinksEncountered);
      }

      ImmutableSet<Path> readOnlyPaths =
          new ParserConfig(getCell(node.getBuildTarget()).getBuckConfig()).getReadOnlyPaths();
      Cell currentCell = cells.get(node.getBuildTarget().getCellPath());

      if (!readOnlyPaths.isEmpty() && currentCell != null) {
        Path cellRootPath = currentCell.getFilesystem().getRootPath();
        for (Path readOnlyPath : readOnlyPaths) {
          if (buildFile.startsWith(cellRootPath.resolve(readOnlyPath))) {
            LOG.debug(
                "Target %s is under a symlink (%s). It will be cached because it belongs " +
                    "under %s, a read-only path white listed in .buckconfing. under [project]" +
                    " read_only_paths",
                node.getBuildTarget(),
                newSymlinksEncountered,
                readOnlyPath);
            return;
          }
        }
      }

      // If we're not explicitly forbidding symlinks, either warn to the console or the log file
      // depennding on the config setting.
      String msg =
          String.format(
              "Disabling caching for target %s, because one or more input files are under a " +
                  "symbolic link (%s). This will severely impact performance! To resolve this, " +
                  "use separate rules and declare dependencies instead of using symbolic links.",
              node.getBuildTarget(),
              newSymlinksEncountered);
      if (allowSymlinks == ParserConfig.AllowSymlinks.WARN) {
        eventBus.post(ConsoleEvent.warning(msg));
      } else {
        LOG.warn(msg);
      }

      buildInputPathsUnderSymlink.add(buildFile);
    }
  }

  private static Map<Path, Path> inputFilesUnderSymlink(
      // We use Collection<Path> instead of Iterable<Path> to prevent
      // accidentally passing in Path, since Path itself is Iterable<Path>.
      Collection<Path> inputs,
      ProjectFilesystem projectFilesystem,
      Map<Path, Optional<Path>> symlinkExistenceCache) throws IOException {
    Map<Path, Path> newSymlinksEncountered = Maps.newHashMap();
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
                projectFilesystem.getPathRelativeToProjectRoot(symlinkTarget).or(symlinkTarget);
            LOG.verbose("Detected symbolic link %s -> %s", subpath, relativeSymlinkTarget);
            newSymlinksEncountered.put(subpath, relativeSymlinkTarget);
            symlinkExistenceCache.put(subpath, Optional.of(relativeSymlinkTarget));
          } else {
            symlinkExistenceCache.put(subpath, Optional.<Path>absent());
          }
        }
      }
    }
    return newSymlinksEncountered;
  }

  @Override
  public void close() throws BuildFileParseException {
    stdout.close();
    stderr.close();
    parsePipeline.close();
    projectBuildFileParserPool.close();

    if (ignoreBuckAutodepsFiles) {
      LOG.debug("Invalidating all caches because buck autodeps ran.");
      permState.invalidateAllCaches();
      return;
    }

    if (!buildInputPathsUnderSymlink.isEmpty()) {
      LOG.debug(
          "Cleaning cache of build files with inputs under symlink %s",
          buildInputPathsUnderSymlink);
    }
    Set<Path> buildInputPathsUnderSymlinkCopy = new HashSet<>(buildInputPathsUnderSymlink);
    buildInputPathsUnderSymlink.clear();
    for (Path buildFilePath : buildInputPathsUnderSymlinkCopy) {
      permState.invalidatePath(buildFilePath);
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractSpeculativeParsing {
    @Value.Parameter
    boolean value();
  }
}
