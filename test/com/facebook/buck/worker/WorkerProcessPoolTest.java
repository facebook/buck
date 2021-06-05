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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.util.Threads;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class WorkerProcessPoolTest {

  private TestThreads testThreads;

  private static final int WAIT_FOR_TEST_THREADS_TIMEOUT = 1_000;

  @Rule public Timeout globalTestTimeout = Timeout.millis(WAIT_FOR_TEST_THREADS_TIMEOUT);

  @Before
  public void setUp() {
    testThreads = new TestThreads();
  }

  @After
  public void tearDown() {
    testThreads.close();
  }

  @Test
  public void testProvidesWorkersAccordingToCapacityThenBlocks() throws Exception {
    int maxWorkers = 3;
    int maxInstances = 2;
    Set<WorkerProcess> createdWorkers = new HashSet<>();
    WorkerProcessPool<DefaultWorkerProcess> pool =
        createPool(maxWorkers, maxInstances, createdWorkers::add);

    AtomicReference<BorrowedWorkerProcess<?>> extraWorkerProcess = new AtomicReference<>();
    // acquire enough workers to exhaust the pool
    acquireWorkersThenRunActionThenRelease(
        pool,
        maxWorkers * maxInstances,
        () ->
            pool.borrowWorkerProcess(1, TimeUnit.MILLISECONDS).ifPresent(extraWorkerProcess::set));

    // no more workers than `capacity` were spawned
    assertThat(createdWorkers.size(), is(maxWorkers));
    assertThat(extraWorkerProcess.get(), is(nullValue()));
  }

  @Test
  public void testReusesWorkerProcesses() throws Exception {
    int maxWorkers = 3;
    int firstBatch = 2;
    int secondBatch = 3;

    Set<WorkerProcess> usedWorkers = new HashSet<>();
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(maxWorkers, 1, usedWorkers::add);

    // create two worker processes, and release them.
    acquireWorkersThenRelease(pool, firstBatch);

    // acquire three more worker processes, two should be reused, and one created
    acquireWorkersThenRelease(pool, secondBatch);

    assertThat(usedWorkers.size(), equalTo(maxWorkers));
  }

  @Test
  public void testLargePool() throws Exception {
    int numConcurrentConsumers = 128;
    int maxInstances = 10;
    Set<WorkerProcess> createdWorkers = new HashSet<>();
    WorkerProcessPool<DefaultWorkerProcess> pool =
        createPool(numConcurrentConsumers * 2, maxInstances, createdWorkers::add);

    acquireWorkersThenRelease(pool, numConcurrentConsumers * maxInstances);

    assertThat(createdWorkers.size(), is(numConcurrentConsumers));
  }

  @Test
  public void testReusesWorkerProcessesInLargePools() throws Exception {
    int numConcurrentConsumers = 128;
    int maxInstances = 5;
    Set<WorkerProcess> createdWorkers = new HashSet<>();
    WorkerProcessPool<DefaultWorkerProcess> pool =
        createPool(numConcurrentConsumers * 2, maxInstances, createdWorkers::add);

    acquireWorkersThenRelease(pool, numConcurrentConsumers / 2);
    acquireWorkersThenRelease(pool, numConcurrentConsumers);

    assertThat(
        createdWorkers.size(),
        equalTo((int) Math.ceil(numConcurrentConsumers * 1. / maxInstances)));
  }

  @Test
  public void destroysProcessOnFailure() throws Exception {
    Set<WorkerProcess> createdWorkers = new HashSet<>();
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(1, 2, createdWorkers::add);

    acquireWorkersThenRelease(pool, 1);
    assertThat(createdWorkers.size(), is(1));

    acquireWorkersThenRunActionThenRelease(
        pool,
        1,
        () ->
            createdWorkers.stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new)
                // closing a worker process will trigger removal from the pool
                .close());

    acquireWorkersThenRelease(pool, 1);

    assertThat(createdWorkers.size(), is(2));
  }

  @Test
  public void returnAndDestroyDoNotInterrupt() throws InterruptedException, IOException {
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(1, 2);

    DefaultWorkerProcess process;
    try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
      process = worker.get();
      process.ensureLaunchAndHandshake();
      Threads.interruptCurrentThread();
    }
    assertThat(Thread.interrupted(), is(true));

    try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
      DefaultWorkerProcess process2 = worker.get();
      process2.ensureLaunchAndHandshake();
      assertThat(process2, is(process));

      process2.close(); // closing a fake worker process triggers destruction
      Threads.interruptCurrentThread();
    }
    assertThat(Thread.interrupted(), is(true));
  }

  @Test
  public void cleansUpDeadProcesses() throws InterruptedException, IOException {
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(1, 2);

    DefaultWorkerProcess process;
    try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
      process = worker.get();
      process.ensureLaunchAndHandshake();
    }
    // only for testing – use after closing BorrowedWorker is illegal.
    // Closing the fake worker will cause isAlive() to return true when returning it to the pool,
    // and to return false when the next consumer retrieves it.
    process.close();

    DefaultWorkerProcess process2;
    try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
      process2 = worker.get();
      process2.ensureLaunchAndHandshake();
    }

    assertThat(process2, is(not(process)));
  }

  @Test
  public void notifiesWaitingThreadsWhenCleaningDeadProcesses() throws Exception {
    int maxWorkers = 2;
    Set<WorkerProcess> createdProcesses = concurrentSet();
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(maxWorkers, 2, createdProcesses::add);

    acquireWorkersThenRunActionThenRelease(
        pool,
        maxWorkers,
        () -> {
          testThreads.startThread(() -> acquireWorkersThenRelease(pool, 1));
          createdProcesses.forEach(WorkerProcess::close);
          Thread.sleep(100); // give test thread opportunity to wait for the lock.
        });

    testThreads.join();
  }

  @Test
  public void canStartupMultipleWorkersInParallel() throws InterruptedException, IOException {
    ArrayBlockingQueue<Future<WorkerProcess>> workers = new ArrayBlockingQueue<>(1);
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(2, 1, workers);

    // thread 1, attempting to borrow a worker
    testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, concurrentSet()));

    // transfer an incomplete future to thread 1. Thread 1 is blocked on @{link Future#get}.
    workers.put(new CompletableFuture<>());

    // transfer a completable future to thread 2
    // .put will block until thread 1 takes the un-completable future added first
    FakeWorkerProcess createdWorker = new FakeWorkerProcess(ImmutableMap.of());
    Future<WorkerProcess> worker = CompletableFuture.completedFuture(createdWorker);
    workers.put(worker);

    // thread 2, attempting to borrow a worker
    Set<WorkerProcess> createdWorkers = concurrentSet();
    Thread secondThread =
        testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));

    // wait until thread 2 ends
    secondThread.join();

    // here, the second thread has finished running, and has thus added the worker it borrowed to
    // `createdWorkers`.
    assertThat(createdWorkers, equalTo(ImmutableSet.of(createdWorker)));
  }

  @Test
  public void canReturnAndBorrowWorkersWhileStartingUpOtherWorkers() throws Exception {
    SynchronousQueue<Future<WorkerProcess>> workers = new SynchronousQueue<>();
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(2, 1, workers);
    CountDownLatch firstThreadWaitingToBorrowProcess = new CountDownLatch(1);
    CountDownLatch secondThreadWaitingForWorker = new CountDownLatch(1);

    AtomicReference<WorkerProcess> firstBorrowedWorker = new AtomicReference<>();
    AtomicReference<WorkerProcess> secondBorrowedWorker = new AtomicReference<>();

    // thread 1, attempting to borrow a worker
    Thread firstThread =
        testThreads.startThread(
            () -> {
              try (BorrowedWorkerProcess<DefaultWorkerProcess> worker =
                  pool.borrowWorkerProcess()) {
                firstThreadWaitingToBorrowProcess.countDown();
                firstBorrowedWorker.set(worker.get());
              }
              try (BorrowedWorkerProcess<DefaultWorkerProcess> worker =
                  pool.borrowWorkerProcess()) {
                secondThreadWaitingForWorker.await();
                secondBorrowedWorker.set(worker.get());
              }
            });

    // transfer a fake worker to thread 1
    workers.put(CompletableFuture.completedFuture(new FakeWorkerProcess(ImmutableMap.of())));

    firstThreadWaitingToBorrowProcess.await();

    // thread 2, attempting to borrow a worker
    testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, concurrentSet()));

    // transfer an incomplete future to thread 2. Thread 2 is blocked on @{link Future#get}.
    workers.put(new CompletableFuture<>());

    // thread 1 continues, returns the worker, and borrows another one
    secondThreadWaitingForWorker.countDown();

    firstThread.join(WAIT_FOR_TEST_THREADS_TIMEOUT);
    // here, thread 1 has borrowed a worker two times, or is blocked returning the first worker.

    assertThat(secondBorrowedWorker.get(), is(firstBorrowedWorker.get()));
  }

  @Test
  public void testEmptyPoolDoesNotBlockForever() throws InterruptedException {
    int CAPACITY = 3;
    int NUM_CONSUMERS = 6;

    AtomicInteger numStartedWorkers = new AtomicInteger();
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();
    Phaser phaser = new Phaser(CAPACITY + 1); // + 1 for test main thread

    WorkerProcessPool<DefaultWorkerProcess> pool =
        createPool(
            CAPACITY,
            1,
            () -> {
              phaser.awaitAdvance(phaser.arriveAndDeregister());
              numStartedWorkers.incrementAndGet();
              throw new IOException("failed to start worker process");
            });

    for (int i = 0; i < NUM_CONSUMERS; ++i) {
      Thread thread = testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
      thread.setUncaughtExceptionHandler((t, e) -> {}); // avoids logging all thrown exceptions
    }

    // wait until pool is exhausted
    phaser.awaitAdvance(phaser.arriveAndDeregister());
    testThreads.join();
    assertThat(numStartedWorkers.get(), equalTo(NUM_CONSUMERS));
  }

  @Test
  public void testPoolClosesCleanyIfNoWorkersUsed() {
    int arbitraryNumber = 16;
    int maxInstances = 2;
    createPool(arbitraryNumber, maxInstances).close();
  }

  @Test
  public void testPoolClosesCleanlyAfterSomeWorkersWereUsedAndReturned() throws Exception {
    int maxWorkers = 6;
    int maxInstances = 2;
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(maxWorkers, maxInstances);
    acquireWorkersThenRelease(pool, maxWorkers * maxInstances - 1);
    pool.close();
  }

  @Test
  public void testPoolClosesCleanlyAfterAllWorkersWereUsedAndReturned() throws Exception {
    int maxWorkers = 6;
    int maxInstances = 2;
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(maxWorkers, maxInstances);
    acquireWorkersThenRelease(pool, maxWorkers * maxInstances);
    pool.close();
  }

  @Test
  public void testPoolClosesCleanlyAfterSomeWorkersWereReused() throws Exception {
    int maxWorkers = 6;
    int maxInstances = 2;
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(maxWorkers, maxInstances);
    for (int i = 0; i < 2; ++i) {
      // first iteration starts up workers, second iteration reuses
      acquireWorkersThenRelease(pool, 5);
    }
    pool.close();
  }

  @Test
  public void testThrowsWhenClosingWithoutAllWorkersReturned()
      throws InterruptedException, IOException {
    int arbitraryNumber = 3;
    int maxInstances = 2;
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(arbitraryNumber, maxInstances);

    List<BorrowedWorkerProcess<DefaultWorkerProcess>> workers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      BorrowedWorkerProcess<DefaultWorkerProcess> workerProcess = pool.borrowWorkerProcess();
      workers.add(workerProcess);
      workerProcess.get(); // use worker
    }

    IllegalStateException illegalStateException =
        assertThrows(IllegalStateException.class, () -> pool.close());
    assertThat(
        illegalStateException.getMessage(),
        equalTo(
            "WorkerProcessPool was still running when shutdown was called. Running workers: "
                + workers.size()));

    workers.forEach(BorrowedWorkerProcess::close);
  }

  @Test
  public void testThrowsWhenClosingWithoutAllUnusedWorkersReturned() throws InterruptedException {
    int arbitraryNumber = 5;
    int maxInstances = 2;
    WorkerProcessPool<DefaultWorkerProcess> pool = createPool(arbitraryNumber, maxInstances);

    List<BorrowedWorkerProcess<DefaultWorkerProcess>> workers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      workers.add(pool.borrowWorkerProcess());
    }

    IllegalStateException illegalStateException =
        assertThrows(IllegalStateException.class, () -> pool.close());
    assertThat(
        illegalStateException.getMessage(),
        equalTo(
            "WorkerProcessPool was still running when shutdown was called. Running workers: "
                + workers.size()));

    workers.forEach(BorrowedWorkerProcess::close);
  }

  private static WorkerProcessPool<DefaultWorkerProcess> createPool(
      int maxWorkers,
      int maxInstances,
      ThrowingSupplier<WorkerProcess, IOException> startWorkerProcess) {
    return new WorkerProcessPool<>(
        maxWorkers,
        maxInstances,
        Hashing.sha1().hashLong(0),
        () -> {
          DefaultWorkerProcess workerProcess = (DefaultWorkerProcess) startWorkerProcess.get();
          workerProcess.ensureLaunchAndHandshake();
          return workerProcess;
        });
  }

  private static WorkerProcessPool<DefaultWorkerProcess> createPool(
      int maxWorkers, int maxInstances) {
    return createPool(maxWorkers, maxInstances, x -> {});
  }

  private static WorkerProcessPool<DefaultWorkerProcess> createPool(
      int maxWorkers, int maxInstances, Consumer<WorkerProcess> onWorkerCreated) {
    return createPool(
        maxWorkers,
        maxInstances,
        () -> {
          FakeWorkerProcess worker = new FakeWorkerProcess(ImmutableMap.of());
          onWorkerCreated.accept(worker);
          return worker;
        });
  }

  private static WorkerProcessPool<DefaultWorkerProcess> createPool(
      int maxWorkers, int maxInstances, BlockingQueue<Future<WorkerProcess>> workers) {
    return createPool(
        maxWorkers,
        maxInstances,
        () -> {
          try {
            return workers.take().get();
          } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static <T> Set<T> concurrentSet() {
    return Collections.newSetFromMap(new ConcurrentHashMap<>());
  }

  private static UnsafeRunnable borrowWorkerProcessWithoutReturning(
      WorkerProcessPool<DefaultWorkerProcess> pool, Set<WorkerProcess> createdWorkers) {
    return () -> {
      BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess();
      DefaultWorkerProcess process = worker.get();
      process.ensureLaunchAndHandshake();
      createdWorkers.add(process);
    };
  }

  private static UnsafeRunnable borrowAndReturnWorkerProcess(
      WorkerProcessPool<DefaultWorkerProcess> pool,
      ConcurrentHashMap<Thread, WorkerProcess> usedWorkers) {
    return () -> {
      try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
        DefaultWorkerProcess workerProcess = worker.get();
        usedWorkers.put(Thread.currentThread(), workerProcess);
        workerProcess.ensureLaunchAndHandshake();
      }
    };
  }

  private static void acquireWorkersThenRelease(
      WorkerProcessPool<DefaultWorkerProcess> pool, int numWorkers) throws Exception {
    acquireWorkersThenRunActionThenRelease(pool, numWorkers, () -> {});
  }

  private static void acquireWorkersThenRunActionThenRelease(
      WorkerProcessPool<DefaultWorkerProcess> pool, int numWorkers, UnsafeRunnable action)
      throws Exception {
    if (numWorkers < 1) {
      action.run();
      return;
    }
    try (BorrowedWorkerProcess<DefaultWorkerProcess> worker = pool.borrowWorkerProcess()) {
      // use worker
      worker.get();
      acquireWorkersThenRunActionThenRelease(pool, numWorkers - 1, action);
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

    @Override
    public void close() {
      isClosed = true;
      for (Thread thread : threads) {
        thread.interrupt();
      }
    }
  }
}
