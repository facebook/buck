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

import static org.junit.Assert.assertThat;

import com.facebook.buck.util.Threads;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.lang.Thread.State;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerProcessPoolTest {

  private TestThreads testThreads;

  @Before
  public void setUp() {
    testThreads = new TestThreads();
  }

  @After
  public void tearDown() {
    testThreads.close();
  }

  @Test
  public void testProvidesWorkersAccordingToCapacityThenBlocks() throws InterruptedException {
    int maxWorkers = 3;
    WorkerProcessPool pool = createPool(maxWorkers);
    Set<WorkerProcess> createdWorkers = concurrentSet();

    for (int i = 0; i < maxWorkers; i++) {
      testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));
    }

    testThreads.awaitThreadStates(State.TERMINATED, State.WAITING);

    State[] testThreadStates =
        testThreads.threads().stream().map(Thread::getState).toArray(State[]::new);
    assertThat(Arrays.asList(testThreadStates), Matchers.everyItem(Matchers.is(State.TERMINATED)));
    assertThat(createdWorkers.size(), Matchers.is(maxWorkers));
  }

  @Test
  public void testReusesWorkerProcesses() throws InterruptedException {
    int maxWorkers = 3;
    WorkerProcessPool pool = createPool(maxWorkers);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    int numThreads = 5;
    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    assertThat(usedWorkers.keySet(), Matchers.equalTo(testThreads.threads()));
    assertThat(
        countDistinct(usedWorkers),
        Matchers.is(
            Matchers.both(Matchers.greaterThan(0)).and(Matchers.lessThanOrEqualTo(maxWorkers))));
  }

  @Test
  public void testUnlimitedPool() throws InterruptedException {
    int numThreads = 20;
    WorkerProcessPool pool = createPool(Integer.MAX_VALUE);
    Set<WorkerProcess> createdWorkers = concurrentSet();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));
    }

    testThreads.join();

    assertThat(createdWorkers.size(), Matchers.is(numThreads));
  }

  @Test
  public void testReusesWorkerProcessesInUnlimitedPools() throws InterruptedException {
    int numThreads = 3;
    WorkerProcessPool pool = createPool(Integer.MAX_VALUE);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    assertThat(
        countDistinct(usedWorkers),
        Matchers.allOf(Matchers.greaterThan(0), Matchers.lessThanOrEqualTo(numThreads)));
  }

  @Test
  public void destroysProcessOnFailure() throws InterruptedException {
    WorkerProcessPool pool = createPool(1);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers)).join();
    assertThat(usedWorkers.size(), Matchers.is(1));

    testThreads.startThread(borrowAndKillWorkerProcess(pool, usedWorkers)).join();
    testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers)).join();

    assertThat(usedWorkers.size(), Matchers.is(3));
    assertThat(countDistinct(usedWorkers), Matchers.is(2));
  }

  @Test
  public void returnAndDestroyDoNotInterrupt() throws InterruptedException, IOException {
    WorkerProcessPool pool = createPool(1);
    WorkerProcess process = pool.borrowWorkerProcess();
    process.ensureLaunchAndHandshake();

    Threads.interruptCurrentThread();
    pool.returnWorkerProcess(process);
    assertThat(Thread.interrupted(), Matchers.is(true));

    WorkerProcess process2 = pool.borrowWorkerProcess();
    process2.ensureLaunchAndHandshake();
    assertThat(process2, Matchers.is(process));

    Threads.interruptCurrentThread();
    pool.destroyWorkerProcess(process2);
    assertThat(Thread.interrupted(), Matchers.is(true));
  }

  @Test
  public void cleansUpDeadProcesses() throws InterruptedException, IOException {
    WorkerProcessPool pool = createPool(1);
    WorkerProcess process = pool.borrowWorkerProcess();
    process.ensureLaunchAndHandshake();
    pool.returnWorkerProcess(process);
    process.close();

    WorkerProcess process2 = pool.borrowWorkerProcess();
    process2.ensureLaunchAndHandshake();
    assertThat(process2, Matchers.is(Matchers.not(process)));
    pool.returnWorkerProcess(process2);
  }

  @Test
  public void canStartupMultipleWorkersInParallel() throws InterruptedException, IOException {
    ArrayBlockingQueue<Future<WorkerProcess>> workers = new ArrayBlockingQueue<>(1);
    WorkerProcessPool pool = createPool(2, workers);

    // thread 1, attempting to borrow a worker
    testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, concurrentSet()));

    // transfer an incomplete future to thread 1. Thread 1 is blocked on @{link Future#get}.
    workers.put(new CompletableFuture<>());

    // thread 2, attempting to borrow a worker
    Set<WorkerProcess> createdWorkers = concurrentSet();
    Thread secondThread =
        testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));

    // transfer a fake worker process to thread 2
    FakeWorkerProcess worker = new FakeWorkerProcess(ImmutableMap.of());
    workers.put(CompletableFuture.completedFuture(worker));

    awaitThreadState(secondThread, State.TERMINATED, State.BLOCKED);

    assertThat(secondThread.getState(), Matchers.is(State.TERMINATED));

    // here, the second thread has finished running, and has thus added the worker it borrowed to
    // `createdWorkers`.
    assertThat(createdWorkers, Matchers.equalTo(ImmutableSet.of(worker)));
  }

  @Test
  public void canReturnAndBorrowWorkersWhileStartingUpOtherWorkers() throws Exception {
    SynchronousQueue<Future<WorkerProcess>> workers = new SynchronousQueue<>();
    WorkerProcessPool pool = createPool(2, workers);
    CountDownLatch secondThreadWaitingForWorker = new CountDownLatch(1);

    AtomicReference<WorkerProcess> firstBorrowedWorker = new AtomicReference<>();
    AtomicReference<WorkerProcess> secondBorrowedWorker = new AtomicReference<>();

    // thread 1, attempting to borrow a worker
    Thread firstThread =
        testThreads.startThread(
            () -> {
              WorkerProcess workerProcess = pool.borrowWorkerProcess();
              firstBorrowedWorker.set(workerProcess);
              secondThreadWaitingForWorker.await();
              pool.returnWorkerProcess(workerProcess);
              secondBorrowedWorker.set(pool.borrowWorkerProcess());
            });

    // transfer a fake worker to thread 1
    workers.put(CompletableFuture.completedFuture(new FakeWorkerProcess(ImmutableMap.of())));

    // thread 2, attempting to borrow a worker
    testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, concurrentSet()));

    // transfer an incomplete future to thread 2. Thread 2 is blocked on @{link Future#get}.
    workers.put(new CompletableFuture<>());

    // thread 1 continues, returns the worker, and borrows another one
    secondThreadWaitingForWorker.countDown();

    awaitThreadState(firstThread, State.TERMINATED, State.BLOCKED);
    // here, thread 1 has borrowed a worker two times, or is blocked returning the first worker.

    assertThat(firstThread.getState(), Matchers.is(State.TERMINATED));
    assertThat(secondBorrowedWorker.get(), Matchers.is(firstBorrowedWorker.get()));
  }

  private static WorkerProcessPool createPool(
      int maxWorkers, ThrowingSupplier<WorkerProcess, IOException> startWorkerProcess) {
    return new WorkerProcessPool(maxWorkers, Hashing.sha1().hashLong(0)) {
      @Override
      protected WorkerProcess startWorkerProcess() throws IOException {
        WorkerProcess workerProcess = startWorkerProcess.get();
        workerProcess.ensureLaunchAndHandshake();
        return workerProcess;
      }
    };
  }

  private static WorkerProcessPool createPool(int maxWorkers) {
    return createPool(maxWorkers, () -> new FakeWorkerProcess(ImmutableMap.of()));
  }

  private static WorkerProcessPool createPool(
      int maxWorkers, BlockingQueue<Future<WorkerProcess>> workers) {
    return createPool(
        maxWorkers,
        () -> {
          try {
            return workers.take().get();
          } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static <T> Set<T> concurrentSet() {
    return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
  }

  private static int countDistinct(ConcurrentHashMap<Thread, WorkerProcess> usedWorkers) {
    return new HashSet<>(usedWorkers.values()).size();
  }

  private static UnsafeRunnable borrowWorkerProcessWithoutReturning(
      WorkerProcessPool pool, Set<WorkerProcess> createdWorkers) {
    return () -> {
      WorkerProcess process = pool.borrowWorkerProcess();
      process.ensureLaunchAndHandshake();
      createdWorkers.add(process);
    };
  }

  private static UnsafeRunnable borrowAndReturnWorkerProcess(
      WorkerProcessPool pool, ConcurrentHashMap<Thread, WorkerProcess> usedWorkers) {
    return () -> {
      WorkerProcess workerProcess = pool.borrowWorkerProcess();
      usedWorkers.put(Thread.currentThread(), workerProcess);
      workerProcess.ensureLaunchAndHandshake();
      pool.returnWorkerProcess(workerProcess);
    };
  }

  private static UnsafeRunnable borrowAndKillWorkerProcess(
      WorkerProcessPool pool, ConcurrentMap<Thread, WorkerProcess> usedWorkers) {
    return () -> {
      WorkerProcess workerProcess = pool.borrowWorkerProcess();
      usedWorkers.put(Thread.currentThread(), workerProcess);
      workerProcess.ensureLaunchAndHandshake();
      pool.destroyWorkerProcess(workerProcess);
    };
  }

  private static void awaitThreadState(Thread thread, State... desiredState)
      throws InterruptedException {
    awaitThreadState(thread, Arrays.asList(desiredState));
  }

  private static void awaitThreadState(Thread thread, List<State> desiredState)
      throws InterruptedException {
    while (!desiredState.contains(thread.getState())) {
      Thread.sleep(1);
    }
  }

  @FunctionalInterface
  interface UnsafeRunnable {
    void run() throws Exception;
  }

  private static class TestThreads implements AutoCloseable {

    private boolean isClosed = false;
    private final Set<Thread> threads = new HashSet<>();

    Thread startThread(UnsafeRunnable target) {
      Preconditions.checkState(!isClosed);
      Thread thread =
          new Thread(
              () -> {
                try {
                  target.run();
                } catch (Exception e) {
                  // Don't rethrow InterruptedException after closing to avoid noise.
                  // These exception are thrown when interrupting test threads that are waiting for
                  // a lock after the end of the test.
                  boolean isInterruptionException =
                      e instanceof InterruptedException
                          || e.getCause() instanceof InterruptedException;
                  if (!isClosed || !isInterruptionException) {
                    throw new RuntimeException(e);
                  }
                }
              });
      threads.add(thread);
      thread.start();
      return thread;
    }

    void join() throws InterruptedException {
      for (Thread thread : threads) {
        thread.join();
      }
    }

    void awaitThreadStates(State... desiredStates) throws InterruptedException {
      List<State> states = Arrays.asList(desiredStates);
      for (Thread thread : threads) {
        awaitThreadState(thread, states);
      }
    }

    Set<Thread> threads() {
      return Collections.unmodifiableSet(threads);
    }

    @Override
    public void close() {
      isClosed = true;
      for (Thread thread : threads) {
        thread.interrupt();
      }
    }
  }
}
