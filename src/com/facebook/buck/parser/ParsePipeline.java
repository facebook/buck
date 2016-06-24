/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This implements a multithreaded pipeline for parsing BUCK files.
 *
 *
 * The high-level flow looks like this:
 *   (in) BuildTarget -> [getRawNodes] -*> [createTargetNode] -> (out) TargetNode
 * (steps in [] have their output cached, -*> means that this step has parallel fanout).
 *
 * The work is simply dumped onto the executor, the {@link ProjectBuildFileParserPool} is used to constrain
 * the number of concurrent active parsers.
 * Within a single pipeline instance work is not duplicated (the **JobsCache variables) are used
 * to make sure we don't schedule the same work more than once), however it's possible for multiple
 * read-only commands to duplicate work.
 */
@ThreadSafe
public class ParsePipeline implements AutoCloseable {

  private static final Logger LOG = Logger.get(ParsePipeline.class);

  private final Object lock = new Object();
  private final Cache cache;
  private final ParserTargetNodeFactory delegate;
  @GuardedBy("lock")
  private final Map<BuildTarget, ListenableFuture<TargetNode<?>>> targetNodeJobsCache;
  @GuardedBy("lock")
  private final Map<Path, ListenableFuture<ImmutableList<Map<String, Object>>>> rawNodeJobsCache;
  private final ListeningExecutorService executorService;
  private final BuckEventBus buckEventBus;
  private final ProjectBuildFileParserPool projectBuildFileParserPool;
  private final boolean speculativeDepsTraversal;
  private final AtomicBoolean shuttingDown;

  /**
   * Create new pipeline for parsing Buck files.
   *
   * @param cache where to persist results.
   * @param delegate where to farm out the creation of TargetNodes to
   * @param executorService executor
   * @param buckEventBus bus to use for parse start/stop events
   * @param projectBuildFileParserPool where to get parsers from
   * @param speculativeDepsTraversal whether to automatically schedule parsing of nodes' deps in the
   *                                 background.
   */
  public ParsePipeline(
      Cache cache,
      ParserTargetNodeFactory delegate,
      ListeningExecutorService executorService,
      BuckEventBus buckEventBus,
      ProjectBuildFileParserPool projectBuildFileParserPool,
      boolean speculativeDepsTraversal) {
    this.cache = cache;
    this.delegate = delegate;
    this.targetNodeJobsCache = new HashMap<>();
    this.rawNodeJobsCache = new HashMap<>();
    this.executorService = executorService;
    this.buckEventBus = buckEventBus;
    this.projectBuildFileParserPool = projectBuildFileParserPool;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.shuttingDown = new AtomicBoolean(false);
  }

  /**
   * Obtain all {@link TargetNode}s from a build file. This may block if the file is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  public ImmutableSet<TargetNode<?>> getAllTargetNodes(
      final Cell cell,
      final Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getAllTargetNodesJob(cell, buildFile).get();
    } catch (Exception e) {
      Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
      throw propagateRuntimeException(e);
    }
  }

  /**
   * Obtain a {@link TargetNode}. This may block if the node is not cached.
   *
   * @param cell the {@link Cell} that the {@link BuildTarget} belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @return the node
   * @throws BuildFileParseException for syntax errors in the build file.
   * @throws BuildTargetException if the buildTarget is malformed
   */
  public final TargetNode<?> getTargetNode(
      final Cell cell,
      final BuildTarget buildTarget
  ) throws BuildFileParseException, BuildTargetException {
    Preconditions.checkState(!shuttingDown.get());

    Optional<TargetNode<?>> cachedNode = cache.lookupTargetNode(cell, buildTarget);
    if (cachedNode.isPresent()) {
      return cachedNode.get();
    }
    try {
      return getTargetNodeJob(cell, buildTarget).get();
    } catch (Exception e) {
      Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), BuildTargetException.class);
      throw propagateRuntimeException(e);
    }
  }

  /**
   * Obtain raw, un-processed contents of a build file. May block if the result is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  public ImmutableList<Map<String, Object>> getRawNodes(
      final Cell cell,
      final Path buildFile) throws BuildFileParseException {
    Preconditions.checkArgument(buildFile.isAbsolute());
    Preconditions.checkState(!shuttingDown.get());

    Optional<ImmutableList<Map<String, Object>>> cachedRawNode = cache.lookupRawNodes(
        cell,
        buildFile);
    if (cachedRawNode.isPresent()) {
      return cachedRawNode.get();
    }
    try {
      return getRawNodesJob(cell, buildFile).get();
    } catch (Exception e) {
      Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
      throw propagateRuntimeException(e);
    }
  }

  /**
   * Asynchronously obtain all {@link TargetNode}s from a build file. This will leverage previously
   * cached raw contents of the file (if present) but will always loop over the contents, so
   * repeated calls (with the same args) are not free.
   *
   * returned future may throw {@link BuildFileParseException} and {@link HumanReadableException}.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return future.
   */
  public ListenableFuture<ImmutableSet<TargetNode<?>>> getAllTargetNodesJob(
      final Cell cell,
      final Path buildFile) {
    ListenableFuture<List<TargetNode<?>>> allNodesList = Futures.transformAsync(
        getRawNodesJob(cell, buildFile),
        new AsyncFunction<ImmutableList<Map<String, Object>>, List<TargetNode<?>>>() {
          @Override
          public ListenableFuture<List<TargetNode<?>>> apply(
              ImmutableList<Map<String, Object>> allRawNodes) throws BuildTargetException {

            if (shuttingDown.get()) {
              return Futures.immediateCancelledFuture();
            }

            ImmutableSet.Builder<ListenableFuture<TargetNode<?>>> allNodes = ImmutableSet.builder();
            for (Map<String, Object> rawNode : allRawNodes) {
              UnflavoredBuildTarget unflavored =
                  parseBuildTargetFromRawRule(cell.getRoot(), rawNode, buildFile);
              BuildTarget target = BuildTarget.of(unflavored);

              allNodes.add(getTargetNodeJob(cell, target, buildFile, rawNode));
            }

            return Futures.allAsList(allNodes.build());
          }
        }
    );
    return Futures.transform(
        allNodesList,
        new Function<List<TargetNode<?>>, ImmutableSet<TargetNode<?>>>() {
          @Override
          public ImmutableSet<TargetNode<?>> apply(List<TargetNode<?>> input) {
            return ImmutableSet.copyOf(input);
          }
        });
  }

  /**
   * Asynchronously get the raw contents of the build file. This leverages the cache.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return future.
   */
  private ListenableFuture<ImmutableList<Map<String, Object>>> getRawNodesJob(
      final Cell cell,
      final Path buildFile) {
    Preconditions.checkArgument(buildFile.isAbsolute());
    return getJobWithCacheLookup(
        buildFile,
        cache.lookupRawNodes(cell, buildFile),
        new Supplier<ListenableFuture<ImmutableList<Map<String, Object>>>>() {
          @Override
          public ListenableFuture<ImmutableList<Map<String, Object>>> get() {
            return computeRawNodes(cell, buildFile);
          }
        },
        rawNodeJobsCache);
  }

  /**
   * Asynchronously get the {@link TargetNode}. This leverages the cache.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @return future.
   *
   * @throws BuildTargetException when the buildTarget is malformed.
   */
  private ListenableFuture<TargetNode<?>> getTargetNodeJob(
      final Cell cell,
      final BuildTarget buildTarget) throws BuildTargetException {
    return getJobWithCacheLookup(
        buildTarget,
        cache.lookupTargetNode(cell, buildTarget),
        new Supplier<ListenableFuture<TargetNode<?>>>() {
          @Override
          public ListenableFuture<TargetNode<?>> get() {
            return computeTargetNode(cell, buildTarget);
          }
        },
        targetNodeJobsCache);
  }

  /**
   * Asynchronously create a {@link TargetNode}. If the supplied buildTarget is cached, this returns
   * the cached value, otherwise it creates the node based on the supplied rawNode.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for.
   * @param buildFile build file the rawNode comes from.
   * @param rawNode raw node to create the TargetNode out of.
   * @return future.
   * @throws BuildTargetException if the buildTarget is malformed.
   */
  private ListenableFuture<TargetNode<?>> getTargetNodeJob(
      final Cell cell,
      final BuildTarget buildTarget,
      final Path buildFile,
      final Map<String, Object> rawNode) throws BuildTargetException {
    return getJobWithCacheLookup(
        buildTarget,
        cache.lookupTargetNode(cell, buildTarget),
        new Supplier<ListenableFuture<TargetNode<?>>>() {
          @Override
          public ListenableFuture<TargetNode<?>> get() {
            return computeTargetNode(
                cell,
                buildTarget,
                buildFile,
                Futures.immediateFuture(rawNode));
          }
        },
        targetNodeJobsCache);
  }

  /**
   * Helper for de-duping jobs against the cache.
   *
   * @param key the key used in the cache,
   * @param cacheLookupResult the result of looking up the result of the computation (if this is
   *                          a hit the method does nothing).
   * @param jobSupplier a supplier to use to create the actual job.
   * @param jobCache the cache containing pending jobs.
   * @param <K> type of key.
   * @param <V> type of resulting computation.
   * @return future describing the job. It can either be an immediate future (result cache hit),
   *         ongoing job (job cache hit) or a new job (miss).
   */
  private <K, V> ListenableFuture<V> getJobWithCacheLookup(
      K key,
      Optional<V> cacheLookupResult,
      Supplier<ListenableFuture<V>> jobSupplier,
      Map<K, ListenableFuture<V>> jobCache) {
    if (cacheLookupResult.isPresent()) {
      return Futures.immediateFuture(cacheLookupResult.get());
    }
    synchronized (lock) {
      Optional<ListenableFuture<V>> cachedJob = Optional.fromNullable(jobCache.get(key));
      if (cachedJob.isPresent()) {
        return cachedJob.get();
      }
      if (shuttingDown.get()) {
        return Futures.immediateCancelledFuture();
      }
      ListenableFuture<V> targetNodeJob = jobSupplier.get();
      jobCache.put(key, targetNodeJob);
      return targetNodeJob;
    }
  }

  /**
   * Low level implementation that does the work to calculate a {@link TargetNode}. This will
   * perform a lookup in the raw node cache, schedule any necessary parsing and perform marshalling.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for.
   * @return future.
   */
  private ListenableFuture<TargetNode<?>> computeTargetNode(
      final Cell cell,
      final BuildTarget buildTarget) {

    final ListenableFuture<Path> buildFile = Futures.transformAsync(
        Futures.immediateFuture(buildTarget),
        new AsyncFunction<BuildTarget, Path>() {
          @Override
          public ListenableFuture<Path> apply(BuildTarget input) throws Exception {
            Path buildFile = cell.getAbsolutePathToBuildFile(buildTarget);
            Preconditions.checkState(buildFile.isAbsolute());
            return Futures.immediateFuture(buildFile);
          }
        });

    ListenableFuture<ImmutableList<Map<String, Object>>> rawNodes = Futures.transformAsync(
        buildFile,
        new AsyncFunction<Path, ImmutableList<Map<String, Object>>>() {
          @Override
          public ListenableFuture<ImmutableList<Map<String, Object>>> apply(
              Path buildFile) {
            return getRawNodesJob(cell, buildFile);
          }
        },
        executorService);

    final ListenableFuture<Map<String, Object>> rawNode = Futures.transformAsync(
        rawNodes,
        new AsyncFunction<ImmutableList<Map<String, Object>>, Map<String, Object>>() {
          @Override
          public ListenableFuture<Map<String, Object>> apply(
              ImmutableList<Map<String, Object>> rawNodes) {
            Optional<Map<String, Object>> rawNode = selectRawNode(buildTarget, rawNodes);
            if (!rawNode.isPresent()) {
              Path buildFilePath = Futures.getUnchecked(buildFile);
              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(
                      buildTarget,
                      BuildTargetPatternParser.forBaseName(buildTarget.getBaseName()),
                      cell.getBuildFileName(),
                      "Defined in file: " + buildFilePath));
            }
            return Futures.immediateFuture(rawNode.get());
          }
        });

    ListenableFuture<TargetNode<?>> targetNodeFuture = Futures.transformAsync(
        buildFile,
        new AsyncFunction<Path, TargetNode<?>>() {
          @Override
          public ListenableFuture<TargetNode<?>> apply(Path buildFile) {
            return computeTargetNode(cell, buildTarget, buildFile, rawNode);
          }
        }
    );

    return targetNodeFuture;
  }

  /**
   * Creates a {@link TargetNode} and de-dupes it against the cache.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for.
   * @param buildFile build file the rawNode comes from.
   * @param rawNode raw node to create the TargetNode out of.
   * @return future.
   */
  private ListenableFuture<TargetNode<?>> computeTargetNode(
      final Cell cell,
      final BuildTarget buildTarget,
      final Path buildFile,
      ListenableFuture<Map<String, Object>> rawNode) {

    ListenableFuture<TargetNode<?>> targetNodeFuture = Futures.transformAsync(
        rawNode,
        new AsyncFunction<Map<String, Object>, TargetNode<?>>() {
          @Override
          public ListenableFuture<TargetNode<?>> apply(Map<String, Object> rawNode)
              throws BuildTargetException {
            if (shuttingDown.get()) {
              return Futures.immediateCancelledFuture();
            }

            try (SimplePerfEvent.Scope scope = Parser.getTargetNodeEventScope(
                buckEventBus,
                buildTarget)) {

              Path pathToCheck = buildTarget.getBasePath();
              if (cell.getFilesystem().isIgnored(pathToCheck)) {
                throw new HumanReadableException(
                    "Content of '%s' cannot be built because" +
                        " it is defined in an ignored directory.",
                    pathToCheck);
              }

              TargetNode<?> targetNode = delegate.createTargetNode(
                  cell,
                  buildFile,
                  buildTarget,
                  rawNode);
              return Futures.<TargetNode<?>>immediateFuture(
                  cache.putTargetNodeIfNotPresent(cell, buildTarget, targetNode));
            }
          }
        },
        executorService);

    if (speculativeDepsTraversal) {
      Futures.addCallback(
          targetNodeFuture,
          new FutureCallback<TargetNode<?>>() {
            @Override
            public void onSuccess(TargetNode<?> result) {
              for (BuildTarget depTarget : result.getDeps()) {
                Path depCellPath = depTarget.getCellPath();
                // TODO(marcinkosiba): Support crossing cell boundary from within the pipeline.
                // Currently the cell name->Cell object mapping is held by the PerBuildState in a
                // non-threadsafe way making it inconvenient to access from the pipeline.
                if (depCellPath.equals(cell.getRoot())) {
                  try {
                    if (depTarget.isFlavored()) {
                      getTargetNodeJob(cell, BuildTarget.of(depTarget.getUnflavoredBuildTarget()));
                    }
                    getTargetNodeJob(cell, depTarget);
                  } catch (BuildTargetException e) {
                    // No biggie, we'll hit the error again in the non-speculative path.
                    LOG.info(e, "Could not schedule speculative parsing for %s", depTarget);
                  }
                }
              }
            }

            @Override
            public void onFailure(Throwable t) {
              // Do nothing.
            }
          },
          executorService);
    }

    return targetNodeFuture;
  }

  /**
   * Invokes the parser to get the raw node description.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to build file.
   * @return future.
   */
  private ListenableFuture<ImmutableList<Map<String, Object>>> computeRawNodes(
      final Cell cell,
      final Path buildFile) {
    return Futures.transformAsync(
        projectBuildFileParserPool.getAllRulesAndMetaRules(cell, buildFile, executorService),
        new AsyncFunction<
            ImmutableList<Map<String, Object>>,
            ImmutableList<Map<String, Object>>>() {
          @Override
          public ListenableFuture<ImmutableList<Map<String, Object>>> apply(
              ImmutableList<Map<String, Object>> rawNodes) throws Exception {
            if (shuttingDown.get()) {
              return Futures.immediateCancelledFuture();
            }
            return Futures.immediateFuture(
                cache.putRawNodesIfNotPresentAndStripMetaEntries(cell, buildFile, rawNodes));
          }
        },
        executorService);
  }

  /**
   * @param cellRoot root path to the cell the rule is defined in.
   * @param map the map of values that define the rule.
   * @param rulePathForDebug path to the build file the rule is defined in, only used for debugging.
   * @return the build target defined by the rule.
   */
  public static UnflavoredBuildTarget parseBuildTargetFromRawRule(
      Path cellRoot,
      Map<String, Object> map,
      Path rulePathForDebug) {
    String basePath = (String) map.get("buck.base_path");
    String name = (String) map.get("name");
    if (basePath == null || name == null) {
      throw new IllegalStateException(
          String.format("Attempting to parse build target from malformed raw data in %s: %s.",
              rulePathForDebug,
              Joiner.on(",").withKeyValueSeparator("->").join(map)));
    }
    Path otherBasePath = cellRoot.relativize(MorePaths.getParentOrEmpty(rulePathForDebug));
    if (!otherBasePath.equals(Paths.get(basePath))) {
      throw new IllegalStateException(
          String.format("Raw data claims to come from [%s], but we tried rooting it at [%s].",
              basePath,
              otherBasePath));
    }
    return UnflavoredBuildTarget.builder(UnflavoredBuildTarget.BUILD_TARGET_PREFIX + basePath, name)
        .setCellPath(cellRoot)
        .build();
  }

  // Un-wraps the ExcecutionException used by Futures to wrap checked exceptions.
  public static final RuntimeException propagateRuntimeException(Throwable e) {
    if (e instanceof ExecutionException | e instanceof UncheckedExecutionException) {
      Throwable cause = e.getCause();
      if (cause != null) {
        Throwables.propagateIfInstanceOf(cause, HumanReadableException.class);
        return Throwables.propagate(cause);
      }
    }

    return Throwables.propagate(e);
  }

  /**
   * Finds the raw node that matches the given {@link BuildTarget} in the map of all raw nodes
   * from a single build file. It is assumed that the raw nodes come from a build file derived
   * from the target.
   *
   * @param target target we're looking for.
   * @param rawNodes all nodes from the build file derived from the target.
   * @return a match if found, Optional.absent otherwise.
   */
  protected final Optional<Map<String, Object>> selectRawNode(
      BuildTarget target,
      ImmutableList<Map<String, Object>> rawNodes) {
    for (Map<String, Object> rawNode : rawNodes) {
      Object shortName = rawNode.get("name");

      if (target.getShortName().equals(shortName)) {
        return Optional.of(rawNode);
      }
    }
    return Optional.absent();
  }

  @Override
  public void close() {
    if (shuttingDown.get()) {
      return;
    }
    shuttingDown.set(true);

    // At this point external callers should not schedule more work, internally job creation
    // should also stop. Any scheduled futures should eventually cancel themselves (all of the
    // AsyncFunctions that interact with the Cache are wired to early-out if `shuttingDown` is
    // true).
    // We could block here waiting for all ongoing work to complete, however the user has already
    // gotten everything they want out of the pipeline, so the only interesting thing that could
    // happen here are exceptions thrown by the ProjectBuildFileParser as its shutting down. These
    // aren't critical enough to warrant bringing down the entire process, as they don't affect the
    // state that has already been extracted from the parser.
  }

  /**
   * Delegate interface to make testing simpler.
   */
  public interface Cache {
    Optional<TargetNode<?>> lookupTargetNode(
        Cell cell,
        BuildTarget target) throws BuildTargetException;

    /**
     * Insert item into the cache if it was not already there.
     * @param cell cell
     * @param target target of the node
     * @param targetNode node to insert
     * @return previous node for the target if the cache contained it, new one otherwise.
     */
    TargetNode<?> putTargetNodeIfNotPresent(
        Cell cell,
        BuildTarget target,
        TargetNode<?> targetNode) throws BuildTargetException;

    Optional<ImmutableList<Map<String, Object>>> lookupRawNodes(
        Cell cell,
        Path buildFile);

    /**
     * Insert item into the cache if it was not already there. The cache will also strip any
     * meta entries from the raw nodes (these are intended for the cache as they contain information
     * about what other files to invalidate entries on).
     *
     * @param cell cell
     * @param buildFile build file
     * @param rawNodes nodes to insert
     * @return previous nodes for the file if the cache contained it, new ones otherwise.
     */
    ImmutableList<Map<String, Object>> putRawNodesIfNotPresentAndStripMetaEntries(
        Cell cell,
        Path buildFile,
        ImmutableList<Map<String, Object>> rawNodes);
  }
}
