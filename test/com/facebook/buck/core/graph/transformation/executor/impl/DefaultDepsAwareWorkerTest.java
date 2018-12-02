/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.graph.transformation.executor.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultDepsAwareWorkerTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();
  private LinkedBlockingDeque<DefaultDepsAwareTask<?>> workQueue;

  private DefaultDepsAwareWorker worker1;
  private DefaultDepsAwareWorker worker2;

  @Before
  public void setUp() {
    workQueue = new LinkedBlockingDeque<>();
    worker1 = new DefaultDepsAwareWorker(workQueue);
    worker2 = new DefaultDepsAwareWorker(workQueue);
  }

  @Test(timeout = 5000)
  public void workerCanRunSingleIndependentWork() throws InterruptedException {
    Semaphore sem = new Semaphore(0);
    workQueue.put(
        DefaultDepsAwareTask.of(
            () -> {
              sem.release();
              return null;
            }));

    Thread testThread =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread.start();

    sem.acquire();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerCanRunDependentWorkFirst() throws InterruptedException {
    AtomicBoolean depTaskDone = new AtomicBoolean();
    DefaultDepsAwareTask<?> depsAwareTask =
        DefaultDepsAwareTask.of(
            () -> {
              depTaskDone.set(true);
              return null;
            });

    Semaphore sem = new Semaphore(0);
    workQueue.put(
        DefaultDepsAwareTask.of(
            () -> {
              assertTrue(depTaskDone.get());
              sem.release();
              return null;
            },
            () -> ImmutableSet.of(depsAwareTask)));

    Thread testThread =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread.start();

    sem.acquire();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerHandlesExceptionDuringGetDeps()
      throws InterruptedException, ExecutionException {
    Exception ex = new Exception();

    expectedException.expectCause(Matchers.sameInstance(ex));

    DefaultDepsAwareTask<?> depsAwareTask =
        DefaultDepsAwareTask.ofThrowing(
            () -> null,
            () -> {
              throw ex;
            });

    workQueue.put(depsAwareTask);

    Thread testThread =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread.start();

    depsAwareTask.getResultFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerPropagatesExceptionDuringGetDepsToParent()
      throws InterruptedException, ExecutionException {
    Exception ex = new Exception();

    expectedException.expectCause(Matchers.sameInstance(ex));

    DefaultDepsAwareTask<?> depsAwareTask1 =
        DefaultDepsAwareTask.ofThrowing(
            () -> null,
            () -> {
              throw ex;
            });

    workQueue.put(depsAwareTask1);

    Thread testThread =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread.start();

    DefaultDepsAwareTask<?> depsAwareTask2 =
        DefaultDepsAwareTask.of(() -> null, () -> ImmutableSet.of(depsAwareTask1));
    workQueue.put(depsAwareTask2);

    depsAwareTask2.getResultFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 50000000)
  public void workCanBeExecutedInMultipleThreadSharingQueue() throws InterruptedException {
    // This tests that if we schedule multiple works, and one worker is occupied, the other worker
    // will pick start the other tasks
    Thread testThread1 =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread1.start();

    Thread testThread2 =
        new Thread(
            () -> {
              try {
                worker2.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });

    Semaphore semDone = new Semaphore(0);
    Semaphore semThread2 = new Semaphore(0);
    Semaphore semStart = new Semaphore(0);

    DefaultDepsAwareTask<?> task1 =
        DefaultDepsAwareTask.of(
            () -> {
              // purposely block this work until we force something to be ran in the other
              // thread
              semStart.release();
              semThread2.acquire();
              semDone.release();
              return null;
            });
    DefaultDepsAwareTask<?> task2 =
        DefaultDepsAwareTask.of(
            () -> {
              semThread2.release();
              return null;
            });

    workQueue.put(task1);

    semStart.acquire();

    workQueue.put(task2);
    testThread2.start();

    semDone.acquire();

    testThread1.interrupt();
    testThread2.interrupt();
  }

  @Test(timeout = 5000)
  public void interruptThreadStopsWorker() throws InterruptedException {

    Semaphore workerStarted = new Semaphore(0);
    Object interruptWaiter = new Object();
    DefaultDepsAwareTask<?> firstTask =
        DefaultDepsAwareTask.of(
            () -> {
              workerStarted.release();
              try {
                interruptWaiter.wait();
              } catch (InterruptedException e) {
                return null;
              }
              return null;
            });

    workQueue.put(firstTask);

    Thread testThread =
        new Thread(
            () -> {
              try {
                worker1.loopForever();
              } catch (InterruptedException e) {
                return;
              }
            });
    testThread.start();

    workerStarted.acquire();

    testThread.interrupt();

    AtomicBoolean taskDone = new AtomicBoolean();
    DefaultDepsAwareTask taskAfterInterrupt =
        DefaultDepsAwareTask.of(
            () -> {
              taskDone.set(true);
              return null;
            });

    workQueue.put(taskAfterInterrupt);

    testThread.join();
    assertFalse(taskDone.get());
  }
}
