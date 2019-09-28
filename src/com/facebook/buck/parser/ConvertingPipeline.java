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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;
import static com.google.common.base.Throwables.throwIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Base class for a parse pipeline that converts data one item at a time.
 *
 * @param <F> Type to convert from (raw nodes, for example)
 * @param <T> Type to convert to (TargetNode, for example)
 * @param <K> Cache key
 */
public abstract class ConvertingPipeline<F, T, K> implements AutoCloseable {
  private static final Logger LOG = Logger.get(ConvertingPipeline.class);

  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

  private final BuckEventBus eventBus;
  private final PipelineNodeCache<K, T> cache;
  private final ConcurrentHashMap<
          Pair<Path, TargetConfiguration>, ListenableFuture<ImmutableList<T>>>
      allNodeCache;
  protected final ListeningExecutorService executorService;
  private final SimplePerfEvent.Scope perfEventScope;
  private final PerfEventId perfEventId;

  /**
   * minimum duration time for performance events to be logged (for use with {@link
   * SimplePerfEvent}s). This is on the base class to make it simpler to enable verbose tracing for
   * all of the parsing pipelines.
   */
  private final long minimumPerfEventTimeMs;

  public ConvertingPipeline(
      ListeningExecutorService executorService,
      Cache<K, T> cache,
      BuckEventBus eventBus,
      Scope perfEventScope,
      PerfEventId perfEventId) {
    this.eventBus = eventBus;
    this.cache = new PipelineNodeCache<>(cache);
    this.allNodeCache = new ConcurrentHashMap<>();
    this.executorService = executorService;
    this.perfEventScope = perfEventScope;
    this.perfEventId = perfEventId;
    this.minimumPerfEventTimeMs = LOG.isVerboseEnabled() ? 0 : 10;
  }

  public ListenableFuture<ImmutableList<T>> getAllNodesJob(
      Cell cell, Path buildFile, TargetConfiguration targetConfiguration) {
    SettableFuture<ImmutableList<T>> future = SettableFuture.create();
    Pair<Path, TargetConfiguration> pathCacheKey = new Pair<>(buildFile, targetConfiguration);
    ListenableFuture<ImmutableList<T>> cachedFuture =
        allNodeCache.putIfAbsent(pathCacheKey, future);

    if (cachedFuture != null) {
      return cachedFuture;
    }

    try {
      ListenableFuture<List<T>> allNodesListJob =
          Futures.transformAsync(
              getItemsToConvert(cell, buildFile),
              allToConvert -> {
                if (shuttingDown()) {
                  return Futures.immediateCancelledFuture();
                }

                ImmutableList.Builder<ListenableFuture<T>> allNodeJobs =
                    ImmutableList.builderWithExpectedSize(allToConvert.size());

                for (F from : allToConvert) {
                  K target =
                      getBuildTarget(
                          cell.getRoot(),
                          cell.getCanonicalName(),
                          buildFile,
                          targetConfiguration,
                          from);
                  allNodeJobs.add(
                      cache.getJobWithCacheLookup(
                          cell, target, () -> dispatchComputeNode(cell, target, from), eventBus));
                }

                return Futures.allAsList(allNodeJobs.build());
              },
              executorService);
      future.setFuture(Futures.transform(allNodesListJob, ImmutableList::copyOf, executorService));
    } catch (Throwable t) {
      future.setException(t);
    }
    return future;
  }

  public ListenableFuture<T> getNodeJob(Cell cell, K buildTarget) throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () ->
            Futures.transformAsync(
                getItemToConvert(cell, buildTarget),
                from -> dispatchComputeNode(cell, buildTarget, from),
                executorService),
        eventBus);
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
  public final T getNode(Cell cell, K buildTarget)
      throws BuildFileParseException, BuildTargetException {
    Preconditions.checkState(!shuttingDown.get());

    try {
      return getNodeJob(cell, buildTarget).get();
    } catch (Exception e) {
      if (e.getCause() != null) {
        throwIfInstanceOf(e.getCause(), BuildFileParseException.class);
        throwIfInstanceOf(e.getCause(), BuildTargetException.class);
      }
      throwIfInstanceOf(e, BuildFileParseException.class);
      throwIfInstanceOf(e, BuildTargetException.class);
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
  }

  protected abstract K getBuildTarget(
      Path root,
      CanonicalCellName cellName,
      Path buildFile,
      TargetConfiguration targetConfiguration,
      F from);

  protected abstract T computeNodeInScope(
      Cell cell, K buildTarget, F rawNode, Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException;

  protected abstract ListenableFuture<ImmutableList<F>> getItemsToConvert(Cell cell, Path buildFile)
      throws BuildTargetException;

  protected abstract ListenableFuture<F> getItemToConvert(Cell cell, K buildTarget)
      throws BuildTargetException;

  /** Do the conversion from input type to output type. */
  protected T computeNode(Cell cell, K buildTarget, F rawNode) throws BuildTargetException {

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scopeIgnoringShortEvents(
            eventBus,
            perfEventId,
            "target",
            buildTarget,
            perfEventScope,
            minimumPerfEventTimeMs,
            TimeUnit.MILLISECONDS)) {
      Function<PerfEventId, Scope> perfEventScopeFunction =
          perfEventId ->
              SimplePerfEvent.scopeIgnoringShortEvents(
                  eventBus, perfEventId, scope, minimumPerfEventTimeMs, TimeUnit.MILLISECONDS);

      return computeNodeInScope(cell, buildTarget, rawNode, perfEventScopeFunction);
    }
  }

  private ListenableFuture<T> dispatchComputeNode(Cell cell, K buildTarget, F from)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    return Futures.immediateFuture(computeNode(cell, buildTarget, from));
  }

  @Override
  public void close() {
    perfEventScope.close();
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

  protected final boolean shuttingDown() {
    return shuttingDown.get();
  }
}
