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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.Threads;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerProcessPoolTest {

  private static final int WAIT_FOR_TEST_THREADS_TIMEOUT = 1000;
  private TestThreads testThreads;

  @Before
  public void setUp() {
    testThreads = new TestThreads();
  }

  @After
  public void tearDown() {
    testThreads.close();
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void testProvidesWorkersAccordingToCapacityThenBlocks() throws InterruptedException {
    int maxWorkers = 3;
    WorkerProcessPool pool = createPool(maxWorkers);
    Set<WorkerProcess> createdWorkers = concurrentSet();

    Phaser phaser = new Phaser(maxWorkers + 1); // +1 for main test thread
    UnsafeRunnable borrowNoReturn = borrowWorkerProcessWithoutReturning(pool, createdWorkers);
    UnsafeRunnable borrowAndWait =
        () -> {
          borrowNoReturn.run();
          phaser.arriveAndDeregister();
        };

    // starts `maxWorkers` threads that exhaust the pool
    for (int i = 0; i < maxWorkers; i++) {
      testThreads.startThread(borrowAndWait);
    }

    // wait for three threads to start spawning a worker
    phaser.arriveAndAwaitAdvance();
    // try to take another worker
    Thread stallingThread =
        testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));

    // give the extra thread a chance to take a worker (which would make the test fail)
    stallingThread.join(100);

    // Register the exception handler with the test phaser.
    // The will transition to 2 parties, as the test main thread is still registered after calling
    // `arriveAndAwaitAdvance()`. The previously running pool clients deregistered themselves.
    phaser.register();
    Throwable throwable[] = {null};
    stallingThread.setUncaughtExceptionHandler(
        (t, e) -> {
          throwable[0] = e;
          phaser.arriveAndDeregister();
        });
    stallingThread.interrupt();
    phaser.awaitAdvance(phaser.arriveAndDeregister());

    // no more workers than `capacity` were spawned
    assertThat(createdWorkers.size(), is(maxWorkers));

    // the fourth thread was actually interrupted
    assertThat(throwable[0].getCause(), instanceOf(InterruptedException.class));
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void testReusesWorkerProcesses() throws InterruptedException {
    int maxWorkers = 3;
    WorkerProcessPool pool = createPool(maxWorkers);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    int numThreads = 5;
    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    assertThat(usedWorkers.keySet(), equalTo(testThreads.threads()));
    assertThat(
        countDistinct(usedWorkers), is(both(greaterThan(0)).and(lessThanOrEqualTo(maxWorkers))));
  }

  @Test
  public void testLargePool() throws InterruptedException {
    int numThreads = 20;
    WorkerProcessPool pool = createPool(numThreads * 2);
    Set<WorkerProcess> createdWorkers = concurrentSet();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));
    }

    testThreads.join();

    assertThat(createdWorkers.size(), is(numThreads));
  }

  @Test
  public void testReusesWorkerProcessesInLargePools() throws InterruptedException {
    int numThreads = 3;
    WorkerProcessPool pool = createPool(numThreads * 2);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    for (int i = 0; i < numThreads; i++) {
      testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers));
    }

    testThreads.join();

    assertThat(countDistinct(usedWorkers), allOf(greaterThan(0), lessThanOrEqualTo(numThreads)));
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void destroysProcessOnFailure() throws InterruptedException {
    WorkerProcessPool pool = createPool(1);
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();

    testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers)).join();
    assertThat(usedWorkers.size(), is(1));

    testThreads.startThread(borrowAndKillWorkerProcess(pool, usedWorkers)).join();
    testThreads.startThread(borrowAndReturnWorkerProcess(pool, usedWorkers)).join();

    assertThat(usedWorkers.size(), is(3));
    assertThat(countDistinct(usedWorkers), is(2));
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void returnAndDestroyDoNotInterrupt() throws InterruptedException, IOException {
    WorkerProcessPool pool = createPool(1);

    WorkerProcess process;
    try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
      process = worker.get();
      process.ensureLaunchAndHandshake();
      Threads.interruptCurrentThread();
    }
    assertThat(Thread.interrupted(), is(true));

    try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
      WorkerProcess process2 = worker.get();
      process2.ensureLaunchAndHandshake();
      assertThat(process2, is(process));

      process2.close(); // closing a fake worker process triggers destruction
      Threads.interruptCurrentThread();
    }
    assertThat(Thread.interrupted(), is(true));
  }

  @Test
  public void cleansUpDeadProcesses() throws InterruptedException, IOException {
    WorkerProcessPool pool = createPool(1);

    WorkerProcess process;
    try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
      process = worker.get();
      process.ensureLaunchAndHandshake();
    }
    // only for testing – use after closing BorrowedWorker is illegal.
    // Closing the fake worker will cause isAlive() to return true when returning it to the pool,
    // and to return false when the next consumer retrieves it.
    process.close();

    WorkerProcess process2;
    try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
      process2 = worker.get();
      process2.ensureLaunchAndHandshake();
    }

    assertThat(process2, is(not(process)));
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void notifiesWaitingThreadsWhenCleaningDeadProcesses() throws InterruptedException {
    WorkerProcessPool pool = createPool(1);

    for (int i = 0; i < 3; i++) {
      testThreads.startThread(
          () -> {
            try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
              worker.get().close();
            }
          });
    }

    testThreads.join();
  }

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void canStartupMultipleWorkersInParallel() throws InterruptedException, IOException {
    ArrayBlockingQueue<Future<WorkerProcess>> workers = new ArrayBlockingQueue<>(1);
    WorkerProcessPool pool = createPool(2, workers);

    // thread 1, attempting to borrow a worker
    testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, concurrentSet()));

    // transfer an incomplete future to thread 1. Thread 1 is blocked on @{link Future#get}.
    workers.put(new CompletableFuture<>());

    // transfer a completable future to thread 2
    FakeWorkerProcess worker = new FakeWorkerProcess(ImmutableMap.of());
    // .put will block until thread 1 takes the un-completable future added first
    workers.put(CompletableFuture.completedFuture(worker));

    // thread 2, attempting to borrow a worker
    Set<WorkerProcess> createdWorkers = concurrentSet();
    Thread secondThread =
        testThreads.startThread(borrowWorkerProcessWithoutReturning(pool, createdWorkers));

    // wait until thread 2 ends
    secondThread.join();

    // here, the second thread has finished running, and has thus added the worker it borrowed to
    // `createdWorkers`.
    assertThat(createdWorkers, equalTo(ImmutableSet.of(worker)));
  }

  @Test
  public void canReturnAndBorrowWorkersWhileStartingUpOtherWorkers() throws Exception {
    SynchronousQueue<Future<WorkerProcess>> workers = new SynchronousQueue<>();
    WorkerProcessPool pool = createPool(2, workers);
    CountDownLatch firstThreadWaitingToBorrowProcess = new CountDownLatch(1);
    CountDownLatch secondThreadWaitingForWorker = new CountDownLatch(1);

    AtomicReference<WorkerProcess> firstBorrowedWorker = new AtomicReference<>();
    AtomicReference<WorkerProcess> secondBorrowedWorker = new AtomicReference<>();

    // thread 1, attempting to borrow a worker
    Thread firstThread =
        testThreads.startThread(
            () -> {
              try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
                firstThreadWaitingToBorrowProcess.countDown();
                firstBorrowedWorker.set(worker.get());
              }
              try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
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

  @Test(timeout = WAIT_FOR_TEST_THREADS_TIMEOUT)
  public void testEmptyPoolDoesNotBlockForever() throws InterruptedException {
    int CAPACITY = 3;
    int NUM_CONSUMERS = 6;

    AtomicInteger numStartedWorkers = new AtomicInteger();
    ConcurrentHashMap<Thread, WorkerProcess> usedWorkers = new ConcurrentHashMap<>();
    Phaser phaser = new Phaser(CAPACITY + 1); // + 1 for test main thread

    WorkerProcessPool pool =
        createPool(
            CAPACITY,
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
    createPool(arbitraryNumber).close();
  }

  @Test
  public void testPoolClosesCleanlyAfterSomeWorkersWereUsedAndReturned()
      throws InterruptedException, IOException {
    int maxWorkers = 6;
    WorkerProcessPool pool = createPool(maxWorkers);
    acquireAndReleaseWorkers(pool, maxWorkers / 2);
    pool.close();
  }

  @Test
  public void testPoolClosesCleanlyAfterAllWorkersWereUsedAndReturned()
      throws InterruptedException, IOException {
    int maxWorkers = 6;
    WorkerProcessPool pool = createPool(maxWorkers);
    acquireAndReleaseWorkers(pool, maxWorkers);
    pool.close();
  }

  @Test
  public void testPoolClosesCleanlyAfterSomeWorkersWereReused()
      throws InterruptedException, IOException {
    int maxWorkers = 6;
    WorkerProcessPool pool = createPool(maxWorkers);
    for (int i = 0; i < 2; ++i) {
      // first iteration starts up workers, second iteration reuses
      acquireAndReleaseWorkers(pool, 2);
    }
    pool.close();
  }

  @Test(expected = IllegalStateException.class)
  public void testThrowsWhenClosingWithoutAllWorkersReturned()
      throws InterruptedException, IOException {
    int arbitraryNumber = 3;
    WorkerProcessPool pool = createPool(arbitraryNumber);
    BorrowedWorkerProcess worker = pool.borrowWorkerProcess();
    worker.get(); // use worker
    pool.close();
    worker.close();
  }

  @Test(expected = IllegalStateException.class)
  public void testThrowsWhenClosingWithoutAllUnusedWorkersReturned() throws InterruptedException {
    int arbitraryNumber = 5;
    WorkerProcessPool pool = createPool(arbitraryNumber);
    BorrowedWorkerProcess worker = pool.borrowWorkerProcess();
    pool.close();
    worker.close();
  }

  private static WorkerProcessPool createPool(
      int maxWorkers, ThrowingSupplier<WorkerProcess, IOException> startWorkerProcess) {
    return new WorkerProcessPool(
        maxWorkers,
        Hashing.sha1().hashLong(0),
        () -> {
          WorkerProcess workerProcess = startWorkerProcess.get();
          workerProcess.ensureLaunchAndHandshake();
          return workerProcess;
        });
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
      BorrowedWorkerProcess worker = pool.borrowWorkerProcess();
      WorkerProcess process = worker.get();
      process.ensureLaunchAndHandshake();
      createdWorkers.add(process);
    };
  }

  private static UnsafeRunnable borrowAndReturnWorkerProcess(
      WorkerProcessPool pool, ConcurrentHashMap<Thread, WorkerProcess> usedWorkers) {
    return () -> {
      try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
        WorkerProcess workerProcess = worker.get();
        usedWorkers.put(Thread.currentThread(), workerProcess);
        workerProcess.ensureLaunchAndHandshake();
      }
    };
  }

  private static UnsafeRunnable borrowAndKillWorkerProcess(
      WorkerProcessPool pool, ConcurrentMap<Thread, WorkerProcess> usedWorkers) {
    return () -> {
      try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
        WorkerProcess workerProcess = worker.get();
        usedWorkers.put(Thread.currentThread(), workerProcess);
        workerProcess.ensureLaunchAndHandshake();
        workerProcess.close(); // will terminate the fake worker
      }
    };
  }

  private static void acquireAndReleaseWorkers(WorkerProcessPool pool, int numWorkers)
      throws InterruptedException, IOException {
    if (numWorkers < 1) {
      return;
    }
    try (BorrowedWorkerProcess worker = pool.borrowWorkerProcess()) {
      // use worker
      worker.get();
      acquireAndReleaseWorkers(pool, numWorkers - 1);
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

    void join(int millis) throws InterruptedException {
      for (Thread thread : threads) {
        thread.join(millis);
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
