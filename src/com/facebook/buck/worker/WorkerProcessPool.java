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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.concurrent.LinkedBlockingStack;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A pool of {@link WorkerProcess} instances.
 *
 * <p>This pool supports acquisition and release of worker processes on different threads. Workers
 * are acquired with {@link #borrowWorkerProcess()}, which returns
 */
@ThreadSafe
public class WorkerProcessPool<T extends WorkerProcess> implements Closeable {

  private static final Logger LOG = Logger.get(WorkerProcessPool.class);

  private final int capacity;
  private final ImmutableList<DefaultWorkerLifecycle<T>> workerLifecycles;
  private final BlockingQueue<WorkerLifecycle<T>> availableWorkers;
  private final HashCode poolHash;

  public WorkerProcessPool(
      int maxWorkers,
      int maxInstancesPerWorker,
      HashCode poolHash,
      ThrowingSupplier<T, IOException> startWorkerProcess) {
    this.capacity = maxWorkers * maxInstancesPerWorker;
    this.availableWorkers = new LinkedBlockingStack<>();
    this.poolHash = poolHash;

    ImmutableList.Builder<DefaultWorkerLifecycle<T>> workerLifecycleBuilder =
        ImmutableList.builderWithExpectedSize(maxWorkers);
    for (int i = 0; i < maxWorkers; i++) {
      DefaultWorkerLifecycle<T> defaultWorkerLifecycle =
          new DefaultWorkerLifecycle<>(startWorkerProcess, availableWorkers::add);
      workerLifecycleBuilder.add(defaultWorkerLifecycle);

      WorkerLifecycle<T> workerLifecycle =
          new ReusableWorkerLifecycle<>(defaultWorkerLifecycle, maxInstancesPerWorker);
      for (int j = 0; j < maxInstancesPerWorker; j++) {
        availableWorkers.add(workerLifecycle);
      }
    }
    this.workerLifecycles = workerLifecycleBuilder.build();
    Preconditions.checkState(workerLifecycles.size() == maxWorkers);
  }

  public WorkerProcessPool(
      int maxWorkers, HashCode poolHash, ThrowingSupplier<T, IOException> startWorkerProcess) {
    this(maxWorkers, 1, poolHash, startWorkerProcess);
  }

  /**
   * If there are available workers, returns one. Otherwise blocks until one becomes available and
   * returns it. Borrowed worker processes must be released by calling {@link
   * BorrowedWorkerProcess#close()} after using them.
   */
  public BorrowedWorkerProcess<T> borrowWorkerProcess() throws InterruptedException {
    return new BorrowedWorkerProcess<>(availableWorkers.take());
  }

  public Optional<BorrowedWorkerProcess<T>> borrowWorkerProcess(int timeout, TimeUnit unit)
      throws InterruptedException {
    return Optional.ofNullable(availableWorkers.poll(timeout, unit))
        .map(BorrowedWorkerProcess::new);
  }

  @Override
  public synchronized void close() {
    Throwable caughtWhileClosing = null;

    // remove all available workers
    int numAvailableWorkers = availableWorkers.drainTo(new ArrayList<>(capacity));
    for (DefaultWorkerLifecycle<T> lifecycle : workerLifecycles) {
      try {
        lifecycle.close();
      } catch (Throwable t) {
        caughtWhileClosing = t;
      }
    }

    int running = capacity - numAvailableWorkers;
    Preconditions.checkState(
        running == 0,
        "WorkerProcessPool was still running when shutdown was called. Running workers: %s",
        running);
    if (caughtWhileClosing != null) {
      throw new RuntimeException(caughtWhileClosing);
    }
  }

  public int getCapacity() {
    return capacity;
  }

  public HashCode getPoolHash() {
    return poolHash;
  }

  private interface WorkerLifecycle<T extends WorkerProcess>
      extends ThrowingSupplier<T, IOException> {

    void makeAvailable();
  }

  /**
   * Represents the lifecycle of one specific worker in a {@link WorkerProcessPool}.
   *
   * <p>Concurrency is controlled by the pool, which supports acquiring and releasing workers with
   * {@link WorkerProcessPool#availableWorkers}.
   *
   * <p>{@link #get()} and {@link #close()} are synchronized to allow closing as part of closing the
   * pool with a consumer trying to acquire a worker in parallel.
   */
  @ThreadSafe
  private static class DefaultWorkerLifecycle<T extends WorkerProcess>
      implements WorkerLifecycle<T>, Closeable {

    private final ThrowingSupplier<T, IOException> startWorkerProcess;
    private final Consumer<DefaultWorkerLifecycle<T>> onWorkerProcessReturn;
    private boolean isClosed = false;
    @Nullable private T workerProcess;

    DefaultWorkerLifecycle(
        ThrowingSupplier<T, IOException> startWorkerProcess,
        Consumer<DefaultWorkerLifecycle<T>> onWorkerProcessReturn) {
      this.startWorkerProcess = startWorkerProcess;
      this.onWorkerProcessReturn = onWorkerProcessReturn;
    }

    /** Allows to retrieve the wrapped worker process, starting it up if necessary. */
    @Override
    public synchronized T get() throws IOException {
      Preconditions.checkState(!isClosed, "Worker was already terminated");
      // If the worker is broken, destroy it
      if (workerProcess != null && !workerProcess.isAlive()) {
        closeWorkerProcess(workerProcess);
        workerProcess = null;
      }

      // start a worker if necessary, this might throw IOException
      if (workerProcess == null) {
        workerProcess = startWorkerProcess.get();
      }

      return workerProcess;
    }

    private void closeWorkerProcess(T workerProcess) {
      try {
        workerProcess.close();
      } catch (Exception ex) {
        LOG.error(ex, "Failed to close dead worker process; ignoring.");
      }
    }

    @Override
    public void makeAvailable() {
      onWorkerProcessReturn.accept(this);
    }

    @Override
    public synchronized void close() {
      Preconditions.checkState(!isClosed, "Worker was already terminated");
      isClosed = true;
      if (workerProcess != null) {
        workerProcess.close();
        workerProcess = null;
      }
    }
  }

  private static class ReusableWorkerLifecycle<T extends WorkerProcess>
      implements WorkerLifecycle<T> {

    private final WorkerLifecycle<T> delegate;
    private final int maxInstances;
    private int inUseCount = 0;
    @Nullable private T workerProcess;

    ReusableWorkerLifecycle(WorkerLifecycle<T> lifecycle, int maxInstances) {
      this.delegate = lifecycle;
      this.maxInstances = maxInstances;
    }

    @Override
    public synchronized T get() throws IOException {
      Preconditions.checkState(inUseCount < maxInstances);
      if (workerProcess == null) {
        workerProcess = delegate.get();
      }

      inUseCount++;
      return workerProcess;
    }

    @Override
    public void makeAvailable() {
      if (inUseCount > 0) {
        inUseCount--;
      }
      delegate.makeAvailable();
    }
  }

  /**
   * Represents a {@link WorkerProcess} borrowed from a {@link WorkerProcessPool}.
   *
   * <p>Ownership must be returned to the pool by calling {@link #close()} after finishing to use
   * the worker.
   *
   * <p>Since {@link BorrowedWorkerProcess} implements {@link Closeable}, it can be used with a
   * try-with-resources statement.
   *
   * <p>{@link BorrowedWorkerProcess} is not thread-safe, and is expected to be used by one thread
   * at a time only. Concurrency control is handled by {@link WorkerProcessPool} and {@link
   * WorkerLifecycle}.
   */
  public static class BorrowedWorkerProcess<T extends WorkerProcess> implements Closeable {

    @Nullable private WorkerLifecycle<T> lifecycle;

    BorrowedWorkerProcess(WorkerLifecycle<T> lifecycle) {
      this.lifecycle = Objects.requireNonNull(lifecycle);
    }

    /** Returns ownership of the borrowed worker process back to the pool it was retrieved from. */
    @Override
    public void close() {
      if (lifecycle != null) {
        WorkerLifecycle<T> lifecycle = this.lifecycle;
        this.lifecycle = null;
        lifecycle.makeAvailable();
      }
    }

    /** Returns an instance of {@link WorkerProcess} wrapped by this object. */
    public T get() throws IOException {
      Preconditions.checkState(lifecycle != null, "BorrowedWorker has already been closed.");
      return lifecycle.get();
    }
  }
}
