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

package com.facebook.buck.worker;

import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;

public class WorkerProcessPoolAsync implements WorkerProcessPool {

  private final HashCode poolHash;
  private final int maxRequests;
  private final ThrowingSupplier<WorkerProcess, IOException> startWorkerProcess;
  private final Semaphore concurrencyLimiter;
  @Nullable private WorkerProcess workerProcess;

  public WorkerProcessPoolAsync(
      int maxRequests,
      HashCode poolHash,
      ThrowingSupplier<WorkerProcess, IOException> startWorkerProcess) {
    this.poolHash = poolHash;
    this.maxRequests = maxRequests;
    this.startWorkerProcess = startWorkerProcess;
    this.concurrencyLimiter = new Semaphore(maxRequests <= 0 ? Integer.MAX_VALUE : maxRequests);
  }

  @Override
  public HashCode getPoolHash() {
    return poolHash;
  }

  @Override
  public int getCapacity() {
    return maxRequests;
  }

  @Override
  public ListenableFuture<WorkerJobResult> submitJob(String expandedJobArgs)
      throws IOException, InterruptedException {
    synchronized (this) {
      if (workerProcess == null || !workerProcess.isAlive()) {
        workerProcess = startWorkerProcess.get();
      }
    }

    concurrencyLimiter.acquire();
    try {
      ListenableFuture<WorkerJobResult> result = workerProcess.submitJob(expandedJobArgs);
      result.addListener(concurrencyLimiter::release, MoreExecutors.directExecutor());
      return result;
    } catch (Throwable t) {
      concurrencyLimiter.release();
      throw t;
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (workerProcess != null) {
        workerProcess.close();
      }
    }
  }
}
