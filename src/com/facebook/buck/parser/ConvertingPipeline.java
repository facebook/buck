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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Base class for a parse pipeline that converts data one item at a time.
 *
 * @param <F> Type to convert from (raw nodes, for example)
 * @param <T> Type to convert to (TargetNode, for example)
 */
public abstract class ConvertingPipeline<F, T> extends ParsePipeline<T> {
  private final BuckEventBus eventBus;
  private final PipelineNodeCache<BuildTarget, T> cache;
  protected final ListeningExecutorService executorService;

  public ConvertingPipeline(
      ListeningExecutorService executorService,
      Cache<BuildTarget, T> cache,
      BuckEventBus eventBus) {
    this.eventBus = eventBus;
    this.cache = new PipelineNodeCache<>(cache);
    this.executorService = executorService;
  }

  @Override
  public ListenableFuture<ImmutableSet<T>> getAllNodesJob(Cell cell, Path buildFile)
      throws BuildTargetException {
    // TODO(csarbora): this hits the chained pipeline before hitting the cache
    ListenableFuture<List<T>> allNodesListJob =
        Futures.transformAsync(
            getItemsToConvert(cell, buildFile),
            allToConvert -> {
              if (shuttingDown()) {
                return Futures.immediateCancelledFuture();
              }

              ImmutableList.Builder<ListenableFuture<T>> allNodeJobs = ImmutableList.builder();

              for (F from : allToConvert) {
                BuildTarget target =
                    getBuildTarget(cell.getRoot(), cell.getCanonicalName(), buildFile, from);
                allNodeJobs.add(
                    cache.getJobWithCacheLookup(
                        cell, target, () -> dispatchComputeNode(cell, target, from), eventBus));
              }

              return Futures.allAsList(allNodeJobs.build());
            },
            executorService);
    return Futures.transform(allNodesListJob, ImmutableSet::copyOf, executorService);
  }

  @Override
  public ListenableFuture<T> getNodeJob(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        () ->
            Futures.transformAsync(
                getItemToConvert(cell, buildTarget),
                from -> dispatchComputeNode(cell, buildTarget, from),
                MoreExecutors.directExecutor()),
        eventBus);
  }

  protected abstract BuildTarget getBuildTarget(
      Path root, Optional<String> cellName, Path buildFile, F from);

  protected abstract T computeNode(Cell cell, BuildTarget buildTarget, F rawNode)
      throws BuildTargetException;

  protected abstract ListenableFuture<ImmutableSet<F>> getItemsToConvert(Cell cell, Path buildFile)
      throws BuildTargetException;

  protected abstract ListenableFuture<F> getItemToConvert(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException;

  private ListenableFuture<T> dispatchComputeNode(Cell cell, BuildTarget buildTarget, F from)
      throws BuildTargetException {
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }
    return Futures.immediateFuture(computeNode(cell, buildTarget, from));
  }
}
