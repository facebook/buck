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

package com.facebook.buck.shell;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public abstract class WorkerProcessPool {

  private final int capacity;
  private final BlockingQueue<WorkerProcess> availableWorkers;
  @GuardedBy("createdWorkers")
  private final List<WorkerProcess> createdWorkers;
  private final HashCode poolHash;

  public WorkerProcessPool(int maxWorkers, HashCode poolHash) {
    this.capacity = maxWorkers;
    this.availableWorkers = new LinkedBlockingQueue<>();
    this.createdWorkers = new ArrayList<>();
    this.poolHash = poolHash;
  }

  public WorkerProcess borrowWorkerProcess()
      throws IOException, InterruptedException {
    WorkerProcess workerProcess = availableWorkers.poll(0, TimeUnit.SECONDS);
    if (workerProcess == null) {
      workerProcess = createNewWorkerIfPossible();
    }
    if (workerProcess != null) {
      return workerProcess;
    }
    return availableWorkers.take();
  }

  private @Nullable WorkerProcess createNewWorkerIfPossible() throws IOException {
    synchronized (createdWorkers) {
      if (createdWorkers.size() == capacity) {
        return null;
      }
      WorkerProcess process = Preconditions.checkNotNull(startWorkerProcess());
      createdWorkers.add(process);
      return process;
    }
  }

  public void returnWorkerProcess(WorkerProcess workerProcess)
      throws InterruptedException {
    synchronized (createdWorkers) {
      Preconditions.checkArgument(
          createdWorkers.contains(workerProcess),
          "Trying to return a foreign WorkerProcess to the pool");
    }
    availableWorkers.put(workerProcess);
  }

  public void close() {
    ImmutableSet<WorkerProcess> processesToClose;
    synchronized (createdWorkers) {
      processesToClose = ImmutableSet.copyOf(createdWorkers);
      Preconditions.checkState(
          availableWorkers.size() == createdWorkers.size(),
          "WorkerProcessPool was still running when shutdown was called.");
    }

    for (WorkerProcess process : processesToClose) {
      process.close();
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
