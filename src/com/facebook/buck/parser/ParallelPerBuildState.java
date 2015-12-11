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
import com.facebook.buck.event.SimplePerfEvent;
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
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
class ParallelPerBuildState implements PerBuildState, AutoCloseable {
  private static final Logger LOG = Logger.get(ParallelPerBuildState.class);
  private final ParallelDaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;
  private final BuckEventBus eventBus;
  private final boolean enableProfiling;

  private final PrintStream stdout;
  private final PrintStream stderr;
  private final Console console;

  private final Map<Path, Cell> cells;
  private final Map<Path, ParserConfig.AllowSymlinks> cellSymlinkAllowability;
  private final ThreadLocal<Map<Cell, ProjectBuildFileParser>> parsers;
  private final Closer closer;
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
  private final Map<Path, Path> symlinkExistenceCache;

  /**
   * The count of build targets in {@code pendingBuildTargets}.  Getting the size is an expensive
   * operation, so we track an upper-bound at all times.
   */
  private final AtomicInteger pendingBuildTargetCount;
  /**
   * The queue of build targets to parse.
   */
  private final LinkedBlockingQueue<BuildTarget> pendingBuildTargets;
  /**
   * Used to notify the caller of {@link #startParsing(Set, ParserConfig, Executor)} when all work
   * on the parsing threads is completed.
   */
  private final CountDownLatch completionNotifier;

  public ParallelPerBuildState(
      ParallelDaemonicParserState permState,
      ConstructorArgMarshaller marshaller,
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling) {
    this.permState = permState;
    this.marshaller = marshaller;
    this.eventBus = eventBus;
    this.enableProfiling = enableProfiling;
    this.cells = new ConcurrentHashMap<>();
    this.cellSymlinkAllowability = new ConcurrentHashMap<>();
    this.parsers = new ThreadLocal<Map<Cell, ProjectBuildFileParser>>() {
      @Override
      protected Map<Cell, ProjectBuildFileParser> initialValue() {
        return new HashMap<>();
      }
    };
    this.buildInputPathsUnderSymlink = Sets.newHashSet();
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

    this.closer = Closer.create();

    this.pendingBuildTargetCount = new AtomicInteger(0);
    this.pendingBuildTargets = new LinkedBlockingQueue<>();
    this.completionNotifier = new CountDownLatch(1);

    register(rootCell);
  }

  /**
   * Setups up the parallel parsing environment and blocks until parsing has finished (successfully
   * or with an error).
   */
  public void startParsing(
      Set<BuildTarget> toExplore,
      ParserConfig parserConfig,
      Executor executor) throws InterruptedException {
    addBuildTargetsToProcess(toExplore);

    // Create the worker threads.
    for (int i = parserConfig.getNumParsingThreads(); i > 0; i--) {
      executor.execute(new BuildTargetParserWorker());
    }

    // Now we wait for parsing to complete.
    completionNotifier.await();
  }

  @Override
  public TargetNode<?> getTargetNode(BuildTarget target)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    Cell owningCell = getCell(target);
    target = target.withoutCell();

    ProjectBuildFileParser parser = getBuildFileParser(owningCell);

    return permState.getTargetNode(
        eventBus,
        owningCell,
        parser,
        target,
        symlinkCheckers);
  }

  @Override
  public ImmutableSet<TargetNode<?>> getAllTargetNodes(Cell cell, Path buildFile)
      throws InterruptedException, IOException, BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    ProjectBuildFileParser parser = getBuildFileParser(cell);

    return permState.getAllTargetNodes(
        eventBus,
        cell,
        parser,
        buildFile,
        symlinkCheckers);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRawNodes(Cell cell, Path buildFile)
      throws InterruptedException, BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    ProjectBuildFileParser parser = getBuildFileParser(cell);

    // The raw nodes are just plain JSON blobs, and so we don't need to check for symlinks
    return permState.getAllRawNodes(cell, parser, buildFile);
  }

  private ProjectBuildFileParser getBuildFileParser(Cell cell) {
    Map<Cell, ProjectBuildFileParser> threadLocalParsers = parsers.get();
    if (threadLocalParsers.containsKey(cell)) {
      return threadLocalParsers.get(cell);
    }

    final ProjectBuildFileParser parser = cell.createBuildFileParser(marshaller, console, eventBus);
    parser.setEnableProfiling(enableProfiling);
    threadLocalParsers.put(cell, parser);
    closer.register(new Closeable() {
      @Override
      public void close() throws IOException {
        try {
          parser.close();
        } catch (BuildFileParseException | InterruptedException e) {
          new IOException(e);
        }
      }
    });
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
    Map<Path, Path> newSymlinksEncountered = Maps.newHashMap();
    if (inputFilesUnderSymlink(
        node.getInputs(),
        node.getRuleFactoryParams().getProjectFilesystem(),
        symlinkExistenceCache,
        newSymlinksEncountered)) {
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
      LOG.warn(
          "Disabling caching for target %s, because one or more input files are under a " +
              "symbolic link (%s). This will severely impact performance! To resolve this, use " +
              "separate rules and declare dependencies instead of using symbolic links.",
          node.getBuildTarget(),
          newSymlinksEncountered);
      buildInputPathsUnderSymlink.add(buildFile);
    }
  }

  private static boolean inputFilesUnderSymlink(
      // We use Collection<Path> instead of Iterable<Path> to prevent
      // accidentally passing in Path, since Path itself is Iterable<Path>.
      Collection<Path> inputs,
      ProjectFilesystem projectFilesystem,
      Map<Path, Path> symlinkExistenceCache,
      Map<Path, Path> newSymlinksEncountered) throws IOException{
    boolean result = false;
    for (Path input : inputs) {
      for (int i = 1; i < input.getNameCount(); i++) {
        Path subpath = input.subpath(0, i);
        Path resolvedSymlink = symlinkExistenceCache.get(subpath);
        if (resolvedSymlink != null) {
          LOG.debug("Detected cached symlink %s -> %s", subpath, resolvedSymlink);
          newSymlinksEncountered.put(subpath, resolvedSymlink);
          result = true;
        } else if (projectFilesystem.isSymLink(subpath)) {
          Path symlinkTarget = projectFilesystem.resolve(subpath).toRealPath();
          Path relativeSymlinkTarget = projectFilesystem.getPathRelativeToProjectRoot(symlinkTarget)
              .or(symlinkTarget);
          LOG.debug("Detected symbolic link %s -> %s", subpath, relativeSymlinkTarget);
          newSymlinksEncountered.put(subpath, relativeSymlinkTarget);
          symlinkExistenceCache.put(subpath, relativeSymlinkTarget);
          result = true;
        }
      }
    }
    return result;
  }

  @Override
  public void close() throws InterruptedException, BuildFileParseException {
    stdout.close();
    stderr.close();

    BuildFileParseException lastSeen = null;
    try {
      closer.close();
    } catch (IOException e) {
      if (e.getCause() instanceof BuildFileParseException) {
        lastSeen = (BuildFileParseException) e.getCause();
      }
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      Throwables.propagate(e);
    }

    LOG.debug(
        "Cleaning cache of build files with inputs under symlink %s",
        buildInputPathsUnderSymlink);
    Set<Path> buildInputPathsUnderSymlinkCopy = new HashSet<>(buildInputPathsUnderSymlink);
    buildInputPathsUnderSymlink.clear();
    for (Path buildFilePath : buildInputPathsUnderSymlinkCopy) {
      permState.invalidatePath(buildFilePath);
    }

    if (lastSeen != null) {
      throw lastSeen;
    }
  }

  /**
   * This should be called when a worker wants to parse a {@link BuildTarget}.
   * @return a processing scope that contains the build target, if available.
   */
  private BuildTargetProcessingScope startProcessingBuildTarget()
      throws InterruptedException, TimeoutException {
    BuildTarget target = pendingBuildTargets.poll(5, TimeUnit.SECONDS); // Arbitrarily choosen.
    if (target == null) {
      throw new TimeoutException();
    }
    return this.new BuildTargetProcessingScope(target);
  }

  private void addBuildTargetsToProcess(Set<BuildTarget> nodes) {
    Preconditions.checkArgument(nodes.size() > 0);
    pendingBuildTargetCount.getAndAdd(nodes.size());
    Preconditions.checkState(pendingBuildTargets.addAll(nodes));
  }

  @NotThreadSafe
  private class BuildTargetProcessingScope implements AutoCloseable {

    private final BuildTarget buildTarget;

    public BuildTargetProcessingScope(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
    }

    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    public void addDepsToProcess(Set<BuildTarget> deps) {
      if (deps.size() == 0) {
        // Nothing to do here.
        return;
      }
      addBuildTargetsToProcess(deps);
    }

    @Override
    public void close() {
      if (pendingBuildTargetCount.getAndDecrement() == 1) {
        completionNotifier.countDown();
      }
    }
  }

  private class BuildTargetParserWorker implements Runnable {
    @Override
    public void run() {
      while (shouldWaitForWork()) {
        try (BuildTargetProcessingScope processingScope = startProcessingBuildTarget()) {
          TargetNode<?> node;
          try (SimplePerfEvent.Scope scope = Parser.getTargetNodeEventScope(
              eventBus,
              processingScope.getBuildTarget())) {
            try {
              node = getTargetNode(processingScope.getBuildTarget());
            } catch (BuildFileParseException | BuildTargetException | IOException e) {
              // It's okay to not raise this further up because in `Parser` we build the target
              // graph and while doing so will hit the same error (the parsing will have been
              // cached).
              abortDoingMoreWork();
              return;
            }

            processingScope.addDepsToProcess(node.getDeps());
          }
        } catch (TimeoutException e) {
          // We timed out waiting to process something on the queue.  This could mean we are done,
          // so run through the while statement again.
          continue;
        } catch (InterruptedException e) {
          abortDoingMoreWork();
          return;
        }
      }
    }

    private boolean shouldWaitForWork() {
      return pendingBuildTargetCount.get() > 0;
    }

    /**
     * Called when all work on all threads should be stopped.  This clears all entries in
     * {@link #pendingBuildTargets} and sets {@link #pendingBuildTargetCount} to {@code 1} so that
     * when {@link BuildTargetProcessingScope#close()} is called, the waiting thread is properly
     * signaled.
     */
    private void abortDoingMoreWork() {
      pendingBuildTargets.clear();
      pendingBuildTargetCount.set(1);
    }
  }
}
