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

import static org.junit.Assert.assertThat;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorkerProcessPoolTest {
  @Test
  public void testProvidesWorkersAccordingToCapacityThenBlocks() throws InterruptedException {
    int maxWorkers = 3;
    final WorkerProcessPool pool = createPool(Optional.of(maxWorkers));
    final Set<WorkerProcess> createdWorkers = concurrentSet();

    Thread[] tasks = new Thread[maxWorkers + 1];
    for (int i = 0; i < tasks.length; i++) {
      tasks[i] = new Thread(new BorrowWorkerProcessWithoutReturning(pool, createdWorkers));
    }

    for (Thread thread: tasks) {
      thread.start();
    }

    for (Thread thread: tasks) {
      thread.join(100);
    }

    assertThat(createdWorkers.size(), Matchers.is(maxWorkers));
  }

  @Test
  public void testReusesWorkerProcesses() throws InterruptedException {
    int maxWorkers = 3;
    final WorkerProcessPool pool = createPool(Optional.of(maxWorkers));
    final ConcurrentHashMap<Runnable, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    Thread[] threads = {
        new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers)),
        new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers)),
        new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers)),
        new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers)),
        new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers)),
    };

    for (Thread thread: threads) {
      thread.start();
    }

    for (Thread thread: threads) {
      thread.join();
    }

    assertThat(usedWorkers.keySet().size(), Matchers.is(threads.length));
    assertThat(
        new HashSet<>(usedWorkers.values()).size(),
        Matchers.allOf(Matchers.greaterThan(0), Matchers.lessThanOrEqualTo(maxWorkers)));
  }

  @Test
  public void testUnlimitedPool() throws InterruptedException {
    int numThreads = 20;
    final WorkerProcessPool pool = createPool(Optional.absent());
    final Set<WorkerProcess> createdWorkers = concurrentSet();

    Thread[] threads = new Thread[numThreads];
    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(new BorrowWorkerProcessWithoutReturning(pool, createdWorkers));
      threads[i].start();
    }

    for (Thread thread: threads) {
      thread.join();
    }

    assertThat(createdWorkers.size(), Matchers.is(numThreads));
  }

  @Test
  public void testReusesWorkerProcessesInUnlimitedPools() throws InterruptedException {
    int numThreads = 3;
    final WorkerProcessPool pool = createPool(Optional.absent());
    final ConcurrentHashMap<Runnable, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    Thread[] threads = new Thread[numThreads];
    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers));
      threads[i].start();
    }

    for (Thread thread: threads) {
      thread.join();
    }

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(new BorrowAndReturnWorkerProcess(pool, usedWorkers));
      threads[i].start();
    }

    for (Thread thread: threads) {
      thread.join();
    }

    assertThat(
        new HashSet<>(usedWorkers.values()).size(),
        Matchers.allOf(Matchers.greaterThan(0), Matchers.lessThanOrEqualTo(numThreads)));

  }

  private static WorkerProcessPool createPool(final Optional<Integer> maxWorkers) {
    return new WorkerProcessPool(maxWorkers) {
      @Override
      protected WorkerProcess startWorkerProcess() throws IOException {
        return new FakeWorkerProcess(ImmutableMap.of());
      }
    };
  }

  private static <T> Set<T> concurrentSet() {
    return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
  }

  private abstract static class Runnable implements java.lang.Runnable {
    public abstract void runUnsafe() throws Exception;

    @Override
    public final void run() {
      try {
        runUnsafe();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  class BorrowWorkerProcessWithoutReturning extends Runnable {
    private final WorkerProcessPool pool;
    private final Set<WorkerProcess> createdWorkers;

    public BorrowWorkerProcessWithoutReturning(
        WorkerProcessPool pool,
        Set<WorkerProcess> createdWorkers) {
      this.pool = pool;
      this.createdWorkers = createdWorkers;
    }

    @Override
    public void runUnsafe() throws Exception {
      createdWorkers.add(pool.borrowWorkerProcess());
    }
  }

  class BorrowAndReturnWorkerProcess extends Runnable {
    private final WorkerProcessPool pool;
    private final Map<Runnable, WorkerProcess> usedWorkers;

    public BorrowAndReturnWorkerProcess(
        WorkerProcessPool pool,
        Map<Runnable, WorkerProcess> usedWorkers) {
      this.pool = pool;
      this.usedWorkers = usedWorkers;
    }

    @Override
    public void runUnsafe() throws Exception {
      WorkerProcess workerProcess = pool.borrowWorkerProcess();
      usedWorkers.put(this, workerProcess);
      pool.returnWorkerProcess(workerProcess);
    }
  }
}
