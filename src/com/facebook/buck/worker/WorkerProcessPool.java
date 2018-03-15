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

package com.facebook.buck.worker;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.concurrent.LinkedBlockingStack;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public abstract class WorkerProcessPool implements Closeable {
  private static final Logger LOG = Logger.get(WorkerProcessPool.class);

  private final int capacity;
  private final BlockingQueue<WorkerProcess> availableWorkers;

  @GuardedBy("createdWorkers")
  private final List<AtomicReference<WorkerProcess>> createdWorkers;

  private final HashCode poolHash;

  public WorkerProcessPool(int maxWorkers, HashCode poolHash) {
    this.capacity = maxWorkers;
    this.availableWorkers = new LinkedBlockingStack<>();
    this.createdWorkers = new ArrayList<>();
    this.poolHash = poolHash;
  }

  /**
   * If there are available workers, returns one. Otherwise blocks until one becomes available and
   * returns it. You must free worker process by calling {@link #returnWorkerProcess(WorkerProcess)}
   * or {@link #destroyWorkerProcess(WorkerProcess)} methods after you finish using it.
   */
  public WorkerProcess borrowWorkerProcess() throws IOException, InterruptedException {
    WorkerProcess workerProcess;
    while ((workerProcess = availableWorkers.poll(0, TimeUnit.SECONDS)) != null) {
      if (workerProcess.isAlive()) {
        break;
      }

      try {
        destroyWorkerProcess(workerProcess);
      } catch (Exception ex) {
        LOG.error(ex, "Failed to close dead worker process; ignoring.");
      }
    }
    if (workerProcess == null) {
      workerProcess = createNewWorkerIfPossible();
    }
    if (workerProcess != null) {
      return workerProcess;
    }
    return availableWorkers.take();
  }

  private @Nullable WorkerProcess createNewWorkerIfPossible() throws IOException {
    AtomicReference<WorkerProcess> ref = new AtomicReference<>();
    synchronized (createdWorkers) {
      if (createdWorkers.size() == capacity) {
        return null;
      }
      createdWorkers.add(ref);
    }

    WorkerProcess process;
    try {
      process = Preconditions.checkNotNull(startWorkerProcess());
    } catch (Throwable t) {
      synchronized (createdWorkers) {
        // AtomicReference#equals compares by instance reference, not by the referenced value.
        // i.e. new AtomicReference(null) != new AtomicReference(null)
        createdWorkers.remove(ref);
      }
      throw t;
    }
    ref.set(process);
    return process;
  }

  public void returnWorkerProcess(WorkerProcess workerProcess) {
    synchronized (createdWorkers) {
      Preconditions.checkArgument(
          findRefForWorkerProcess(workerProcess) != null,
          "Trying to return a foreign WorkerProcess to the pool");
    }
    // Note: put() can throw, offer doesn't.
    boolean added = availableWorkers.offer(workerProcess);
    Preconditions.checkState(added, "Should have had enough room for existing worker");
  }

  @Nullable
  private AtomicReference<WorkerProcess> findRefForWorkerProcess(WorkerProcess workerProcess) {
    for (AtomicReference<WorkerProcess> ref : createdWorkers) {
      if (ref.get() == workerProcess) {
        return ref;
      }
    }
    return null;
  }

  // Same as returnWorkerProcess, except this assumes the worker is borked and should be terminated
  // with prejudice.
  public void destroyWorkerProcess(WorkerProcess workerProcess) {
    synchronized (createdWorkers) {
      boolean removed = createdWorkers.remove(findRefForWorkerProcess(workerProcess));
      Preconditions.checkArgument(removed, "Trying to return a foreign WorkerProcess to the pool");
    }
    workerProcess.close();
  }

  @Override
  public void close() {
    ImmutableSet<WorkerProcess> processesToClose;
    synchronized (createdWorkers) {
      processesToClose =
          createdWorkers.stream().map(AtomicReference::get).collect(ImmutableSet.toImmutableSet());
      Preconditions.checkState(
          availableWorkers.size() == createdWorkers.size(),
          "WorkerProcessPool was still running when shutdown was called.");
    }

    Exception ex = null;
    for (WorkerProcess process : processesToClose) {
      Preconditions.checkState(
          process != null,
          "WorkerProcessPool: a worker was still starting up when shutdown was called.");
      try {
        process.close();
      } catch (Exception t) {
        ex = t;
      }
    }

    if (ex != null) {
      throw new RuntimeException(ex);
    }
  }

  public int getCapacity() {
    return capacity;
  }

  protected abstract WorkerProcess startWorkerProcess() throws IOException;

  public HashCode getPoolHash() {
    return poolHash;
  }
}
