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

package com.facebook.buck.workertool.impl;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that provides an instance of {@link WorkerProcessPool} based on the startup command
 * client provide.
 */
public class WorkerToolPoolFactory {

  private WorkerToolPoolFactory() {}

  /**
   * Returns an existing {@link WorkerProcessPool} for the given {@code startupCommand} if one
   * exists, otherwise creates a new one.
   */
  public static WorkerProcessPool<WorkerToolExecutor> getPool(
      IsolatedExecutionContext context,
      ImmutableList<String> startupCommand,
      ThrowingSupplier<WorkerToolExecutor, IOException> startWorkerProcess,
      int capacity,
      int maxInstancesPerWorker) {

    String key = String.join(" ", startupCommand);
    HashCode workerHash = Hashing.sha1().hashString(key, StandardCharsets.UTF_8);

    ConcurrentMap<String, WorkerProcessPool<WorkerToolExecutor>> poolMap =
        context.getWorkerToolPools();

    // If the worker pool has a different hash, recreate the pool.
    WorkerProcessPool<WorkerToolExecutor> pool = poolMap.get(key);
    if (pool != null && !pool.getPoolHash().equals(workerHash)) {
      if (poolMap.remove(key, pool)) {
        pool.close();
      }
      pool = poolMap.get(key);
    }

    if (pool == null) {
      pool =
          createPool(poolMap, capacity, maxInstancesPerWorker, key, workerHash, startWorkerProcess);
    }
    return pool;
  }

  private static WorkerProcessPool<WorkerToolExecutor> createPool(
      ConcurrentMap<String, WorkerProcessPool<WorkerToolExecutor>> poolMap,
      int capacity,
      int maxInstancesPerWorker,
      String key,
      HashCode workerHash,
      ThrowingSupplier<WorkerToolExecutor, IOException> startWorkerProcess) {

    WorkerProcessPool<WorkerToolExecutor> newPool =
        new WorkerProcessPool<>(capacity, maxInstancesPerWorker, workerHash, startWorkerProcess);
    WorkerProcessPool<WorkerToolExecutor> previousPool = poolMap.putIfAbsent(key, newPool);
    // If putIfAbsent does not return null, then that means another thread beat this thread
    // into putting an WorkerProcessPool in the map for this key. If that's the case, then we
    // should ignore newPool and return the existing one.
    return previousPool == null ? newPool : previousPool;
  }
}
