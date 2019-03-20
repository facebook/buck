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

import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask.DepsSupplier;
import com.facebook.buck.core.graph.transformation.executor.impl.AbstractDepsAwareTask.TaskStatus;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DepsAwareWorkerTest<TaskType extends AbstractDepsAwareTask<?, TaskType>> {

  @Parameterized.Parameters
  public static Iterable<Object[]> params() {
    return Arrays.asList(
        new Object[][] {
          {
            (Function<
                    LinkedBlockingDeque<DefaultDepsAwareTask<? super Object>>,
                    AbstractDepsAwareWorker<?>>)
                defaultDepsAwareTasks -> new DefaultDepsAwareWorker<Object>(defaultDepsAwareTasks),
            (BiFunction<
                    Callable<Object>,
                    DepsAwareTask.DepsSupplier<DefaultDepsAwareTask<Object>>,
                    DefaultDepsAwareTask<Object>>)
                (callable, depsSupplier) -> DefaultDepsAwareTask.of(callable, depsSupplier)
          },
          {
            (Function<
                    LinkedBlockingDeque<DefaultDepsAwareTask<? super Object>>,
                    AbstractDepsAwareWorker<?>>)
                defaultDepsAwareTasks ->
                    new DefaultDepsAwareWorkerWithLocalStack<Object>(defaultDepsAwareTasks),
            (BiFunction<
                    Callable<Object>,
                    DepsAwareTask.DepsSupplier<DefaultDepsAwareTask<Object>>,
                    DefaultDepsAwareTask<Object>>)
                (callable, depsSupplier) -> DefaultDepsAwareTask.of(callable, depsSupplier)
          },
          {
            (Function<
                    LinkedBlockingDeque<ToposortBasedDepsAwareTask<? super Object>>,
                    AbstractDepsAwareWorker<?>>)
                defaultDepsAwareTasks -> new ToposortDepsAwareWorker<Object>(defaultDepsAwareTasks),
            (BiFunction<
                    Callable<Object>,
                    DepsAwareTask.DepsSupplier<ToposortBasedDepsAwareTask<Object>>,
                    ToposortBasedDepsAwareTask<Object>>)
                (callable, depsSupplier) -> ToposortBasedDepsAwareTask.of(callable, depsSupplier)
          }
        });
  }

  private final Function<LinkedBlockingDeque<TaskType>, AbstractDepsAwareWorker> workerConstructor;
  private final BiFunction<Callable<?>, DepsSupplier<TaskType>, TaskType> taskCreator;

  public DepsAwareWorkerTest(
      Function<LinkedBlockingDeque<TaskType>, AbstractDepsAwareWorker> workerConstructor,
      BiFunction<Callable<?>, DepsSupplier<TaskType>, TaskType> taskCreator) {
    this.workerConstructor = workerConstructor;
    this.taskCreator = taskCreator;
  }

  @Rule public ExpectedException expectedException = ExpectedException.none();
  private LinkedBlockingDeque<TaskType> workQueue;

  private AbstractDepsAwareWorker<?> worker1;
  private AbstractDepsAwareWorker<?> worker2;

  @Before
  public void setUp() {
    workQueue = new LinkedBlockingDeque<>();
    worker1 = workerConstructor.apply(workQueue);
    worker2 = workerConstructor.apply(workQueue);
  }

  @Test(timeout = 5000)
  public void workerCanRunSingleIndependentWork() throws InterruptedException {
    Semaphore sem = new Semaphore(0);
    TaskType task =
        createTask(
            () -> {
              sem.release();
              return null;
            });

    Verify.verify(task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(task);

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
  public void workerCanRunPrereqWorkFirst() throws InterruptedException, ExecutionException {
    AtomicBoolean prereqTaskDone = new AtomicBoolean();
    TaskType depsAwareTask1 =
        createTask(
            () -> {
              prereqTaskDone.set(true);
              return null;
            });

    Semaphore sem = new Semaphore(0);

    TaskType depsAwareTask2 =
        createTask(
            () -> {
              assertTrue(prereqTaskDone.get());
              sem.release();
              return null;
            },
            DepsSupplier.of(() -> ImmutableSet.of(depsAwareTask1)));
    depsAwareTask2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED);
    workQueue.put(depsAwareTask2);

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
    depsAwareTask2.getFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerCanRunDepWorkFirst() throws InterruptedException, ExecutionException {
    AtomicBoolean depTaskDone = new AtomicBoolean();
    TaskType depsAwareTask1 =
        createTask(
            () -> {
              depTaskDone.set(true);
              return null;
            });

    Semaphore sem = new Semaphore(0);

    TaskType depsAwareTask2 =
        createTask(
            () -> {
              assertTrue(depTaskDone.get());
              sem.release();
              return null;
            },
            DepsSupplier.of(ImmutableSet::of, () -> ImmutableSet.of(depsAwareTask1)));
    depsAwareTask2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED);
    workQueue.put(depsAwareTask2);

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
    depsAwareTask2.getFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerCanRunPrereqBeforeDep() throws InterruptedException, ExecutionException {
    AtomicBoolean prereqTaskDone = new AtomicBoolean();
    TaskType depsAwareTask1 =
        createTask(
            () -> {
              prereqTaskDone.set(true);
              return null;
            });

    Semaphore sem = new Semaphore(0);

    TaskType depsAwareTask2 =
        createTask(
            () -> {
              assertTrue(prereqTaskDone.get());
              sem.release();
              return null;
            },
            DepsSupplier.of(
                () -> ImmutableSet.of(depsAwareTask1),
                () -> {
                  // prereq should run before getdeps
                  assertTrue(prereqTaskDone.get());
                  return ImmutableSet.of();
                }));
    depsAwareTask2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED);
    workQueue.put(depsAwareTask2);

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
    depsAwareTask2.getFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
  public void workerHandlesExceptionDuringGetPrereq()
      throws InterruptedException, ExecutionException {
    Exception ex = new Exception();

    expectedException.expectCause(Matchers.sameInstance(ex));

    TaskType depsAwareTask =
        createTask(
            () -> null,
            DepsSupplier.of(
                () -> {
                  throw ex;
                }));

    Verify.verify(
        depsAwareTask.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
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
  public void workerHandlesExceptionDuringGetDep() throws InterruptedException, ExecutionException {
    Exception ex = new Exception();

    expectedException.expectCause(Matchers.sameInstance(ex));

    TaskType depsAwareTask =
        createTask(
            () -> null,
            DepsSupplier.of(
                ImmutableSet::of,
                () -> {
                  throw ex;
                }));

    Verify.verify(
        depsAwareTask.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
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

    TaskType depsAwareTask1 =
        createTask(
            () -> null,
            DepsSupplier.of(
                () -> {
                  throw ex;
                }));

    Verify.verify(
        depsAwareTask1.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
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

    TaskType depsAwareTask2 =
        createTask(() -> null, DepsSupplier.of(() -> ImmutableSet.of(depsAwareTask1)));

    Verify.verify(
        depsAwareTask2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(depsAwareTask2);

    depsAwareTask2.getResultFuture().get();
    testThread.interrupt();
  }

  @Test(timeout = 5000)
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

    TaskType task1 =
        createTask(
            () -> {
              // purposely block this work until we force something to be ran in the other
              // thread
              semStart.release();
              semThread2.acquire();
              semDone.release();
              return null;
            });
    TaskType task2 =
        createTask(
            () -> {
              semThread2.release();
              return null;
            });

    Verify.verify(task1.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(task1);

    semStart.acquire();

    Verify.verify(task2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
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
    TaskType firstTask =
        createTask(
            () -> {
              workerStarted.release();
              try {
                interruptWaiter.wait();
              } catch (InterruptedException e) {
                return null;
              }
              return null;
            });

    Verify.verify(firstTask.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
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
    // create a task that runs in the worker and verifies that it got the interrupted flag.
    // We could race with the interrupt due to implementation of the LinkedBlockingDeque.
    TaskType waitForInterrupt =
        createTask(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  Thread.sleep(50);
                }
              } catch (InterruptedException e) {
                // reset the interrupt flag
                Thread.currentThread().interrupt();
              }
              return null;
            });
    Verify.verify(
        waitForInterrupt.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(waitForInterrupt);

    TaskType taskAfterInterrupt =
        createTask(
            () -> {
              taskDone.set(true);
              return null;
            });

    Verify.verify(
        taskAfterInterrupt.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(taskAfterInterrupt);

    testThread.join();
    assertFalse(taskDone.get());
  }

  @Test(timeout = 5000)
  @SuppressWarnings("PMD.EmptyWhileStmt")
  public void workerIsNotDuplicateScheduled() throws InterruptedException {
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

    Semaphore getDepsRan = new Semaphore(0);

    TaskType task1 = createTask(() -> null);
    TaskType task2 =
        createTask(
            () -> {
              while (true) {}
            },
            DepsSupplier.of(
                () -> {
                  getDepsRan.release();
                  return ImmutableSet.of(task1);
                }));

    // pretend task1 is scheduled
    Verify.verify(task1.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    Verify.verify(task2.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED));
    workQueue.put(task2);

    getDepsRan.acquire();

    assertFalse(workQueue.contains(task1));
  }

  TaskType createTask(Callable<?> callable) {
    return createTask(callable, DepsSupplier.of(ImmutableSet::of));
  }

  TaskType createTask(Callable<?> callable, DepsSupplier<TaskType> depsSupplier) {
    return taskCreator.apply(callable, depsSupplier);
  }
}
