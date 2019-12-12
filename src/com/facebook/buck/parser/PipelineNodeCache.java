/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

class PipelineNodeCache<K, T> {
  private final Cache<K, T> cache;
  protected final ConcurrentMap<K, ListenableFuture<T>> jobsCache;
  private final Predicate<T> targetNodeIsConfiguration;

  public PipelineNodeCache(Cache<K, T> cache, Predicate<T> targetNodeIsConfiguration) {
    this.targetNodeIsConfiguration = targetNodeIsConfiguration;
    this.jobsCache = new ConcurrentHashMap<>();
    this.cache = cache;
  }

  /**
   * Helper for de-duping jobs against the cache.
   *
   * @param jobSupplier a supplier to use to create the actual job.
   * @return future describing the job. It can either be an immediate future (result cache hit),
   *     ongoing job (job cache hit) or a new job (miss).
   */
  protected final ListenableFuture<T> getJobWithCacheLookup(
      Cell cell, K key, JobSupplier<T> jobSupplier, BuckEventBus eventBus) {

    // We use a SettableFuture to resolve any races between threads that are trying to create the
    // job for the given key. The SettableFuture is cheap to throw away in case we didn't "win"
    // and can be easily "connected" to a future that actually does work in case we did.
    SettableFuture<T> resultFuture = SettableFuture.create();
    ListenableFuture<T> resultFutureInCache = jobsCache.putIfAbsent(key, resultFuture);
    if (resultFutureInCache != null) {
      // Another thread succeeded in putting the new value into the cache.
      return resultFutureInCache;
    }
    // Ok, "our" candidate future went into the jobsCache, schedule the job and 'chain' the result
    // to the SettableFuture, so that anyone else waiting on it will get the same result.

    try {
      Optional<T> cacheLookupResult = cache.lookupComputedNode(cell, key, eventBus);
      if (cacheLookupResult.isPresent()) {
        resultFuture.set(cacheLookupResult.get());
      } else {
        ListenableFuture<T> job = jobSupplier.get();
        ListenableFuture<T> cacheJob =
            Futures.transformAsync(
                job,
                input -> {
                  boolean targetNodeIsConfiguration = this.targetNodeIsConfiguration.test(input);
                  return Futures.immediateFuture(
                      cache.putComputedNodeIfNotPresent(
                          cell, key, input, targetNodeIsConfiguration, eventBus));
                },
                MoreExecutors.directExecutor());
        resultFuture.setFuture(cacheJob);
      }
    } catch (Throwable t) {
      resultFuture.setException(t);
      throw t;
    }
    return resultFuture;
  }

  protected interface JobSupplier<V> {
    ListenableFuture<V> get() throws BuildTargetException;
  }

  public interface Cache<K, V> {
    Optional<V> lookupComputedNode(Cell cell, K target, BuckEventBus eventBus)
        throws BuildTargetException;

    /**
     * Insert item into the cache if it was not already there.
     *
     * @param cell cell
     * @param target target of the node
     * @param targetNode node to insert
     * @param targetIsConfiguration target is configuration target
     * @return previous node for the target if the cache contained it, new one otherwise.
     */
    V putComputedNodeIfNotPresent(
        Cell cell, K target, V targetNode, boolean targetIsConfiguration, BuckEventBus eventBus)
        throws BuildTargetException;
  }
}
