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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.PipelineNodeCache.JobSupplier;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.util.List;

/**
 * Base class for a parse pipeline that converts data one item at a time.
 * @param <F> Type to convert from (raw nodes, for example)
 * @param <T> Type to convert to (TargetNode, for example)
 */
public abstract class ConvertingPipeline<F, T> extends ParsePipeline<T> {
  private final PipelineNodeCache<BuildTarget, T> cache;
  protected final ListeningExecutorService executorService;

  public ConvertingPipeline(
      ListeningExecutorService executorService,
      Cache<BuildTarget, T> cache) {
    this.cache = new PipelineNodeCache<>(cache);
    this.executorService = executorService;
  }

  @Override
  public ListenableFuture<ImmutableSet<T>> getAllNodesJob(
      final Cell cell,
      final Path buildFile) throws BuildTargetException {
    // TODO(csarbora): this hits the chained pipeline before hitting the cache
    ListenableFuture<List<T>> allNodesListJob = Futures.transformAsync(
        getItemsToConvert(cell, buildFile),
        new AsyncFunction<ImmutableSet<F>, List<T>>() {
          @Override
          public ListenableFuture<List<T>> apply(ImmutableSet<F> allToConvert)
              throws BuildTargetException {
            if (shuttingDown()) {
              return Futures.immediateCancelledFuture();
            }

            ImmutableList.Builder<ListenableFuture<T>> allNodeJobs = ImmutableList.builder();

            for (final F from : allToConvert) {
              if (isValid(from)) {
                final BuildTarget target = getBuildTarget(cell.getRoot(), buildFile, from);
                allNodeJobs.add(
                    cache.getJobWithCacheLookup(
                        cell,
                        target,
                        new JobSupplier<T>() {
                          @Override
                          public ListenableFuture<T> get() throws BuildTargetException {
                            if (shuttingDown()) {
                              return Futures.immediateCancelledFuture();
                            }
                            return dispatchComputeNode(cell, target, from);
                          }
                        }));
              }
            }

            return Futures.allAsList(allNodeJobs.build());
          }
        }
    );
    return Futures.transform(
        allNodesListJob,
        new Function<List<T>, ImmutableSet<T>>() {
          @Override
          public ImmutableSet<T> apply(List<T> input) {
            return ImmutableSet.copyOf(input);
          }
        }
    );
  }

  @Override
  public ListenableFuture<T> getNodeJob(
      final Cell cell,
      final BuildTarget buildTarget) throws BuildTargetException {
    return cache.getJobWithCacheLookup(
        cell,
        buildTarget,
        new JobSupplier<T>() {
          @Override
          public ListenableFuture<T> get() throws BuildTargetException {
            return Futures.transformAsync(
                getItemToConvert(cell, buildTarget),
                new AsyncFunction<F, T>() {
                  @Override
                  public ListenableFuture<T> apply(F from) throws BuildTargetException {
                    return dispatchComputeNode(cell, buildTarget, from);
                  }
                }
            );
          }
        });
  }

  protected boolean isValid(F from) {
    return from != null;
  }

  protected abstract BuildTarget getBuildTarget(
      Path root,
      Path buildFile,
      F from);

  protected abstract T computeNode(
      Cell cell,
      BuildTarget buildTarget,
      F rawNode) throws BuildTargetException;

  protected abstract ListenableFuture<ImmutableSet<F>> getItemsToConvert(
      Cell cell,
      Path buildFile) throws BuildTargetException;

  protected abstract ListenableFuture<F> getItemToConvert(
      Cell cell,
      BuildTarget buildTarget) throws BuildTargetException;

  private ListenableFuture<T> dispatchComputeNode(
      Cell cell,
      BuildTarget buildTarget,
      F from) throws BuildTargetException {
    // TODO(csarbora): would be nice to have the first half of this function pulled up into base
    if (shuttingDown()) {
      return Futures.immediateCancelledFuture();
    }

    if (!isValid(from)) {
      throw new NoSuchBuildTargetException(buildTarget);
    }

    Path pathToCheck = buildTarget.getBasePath();
    if (cell.getFilesystem().isIgnored(pathToCheck)) {
      throw new HumanReadableException(
          "Content of '%s' cannot be built because" +
              " it is defined in an ignored directory.",
          pathToCheck);
    }

    return Futures.immediateFuture(
        computeNode(cell, buildTarget, from));
  }

}
