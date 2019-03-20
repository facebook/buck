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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DepsAwareExecutorTest<TaskType extends DepsAwareTask<Object, TaskType>> {

  private static final int NUMBER_OF_THREADS = 2;

  @Parameterized.Parameters
  public static Iterable<Object[]> params() {

    return Arrays.asList(
        new Object[][] {
          {
            (Supplier<DepsAwareExecutor<?, ?>>) () -> DefaultDepsAwareExecutor.of(NUMBER_OF_THREADS)
          },
          {
            (Supplier<DepsAwareExecutor<?, ?>>)
                () -> DefaultDepsAwareExecutorWithLocalStack.of(NUMBER_OF_THREADS)
          },
          {
            (Supplier<DepsAwareExecutor<?, ?>>)
                () -> JavaExecutorBackedDefaultDepsAwareExecutor.of(NUMBER_OF_THREADS)
          },
          {
            (Supplier<DepsAwareExecutor<?, ?>>)
                () -> ToposortBasedDepsAwareExecutor.of(NUMBER_OF_THREADS)
          },
        });
  }

  public DepsAwareExecutorTest(Supplier<DepsAwareExecutor<Object, TaskType>> executorSupplier) {
    this.executor = executorSupplier.get();
  }

  private DepsAwareExecutor<Object, TaskType> executor;

  public @Rule ExpectedException expectedException = ExpectedException.none();

  @After
  public void cleanUp() {
    executor.close();
  }

  @Test
  public void submittedTaskRuns() throws InterruptedException, ExecutionException {
    AtomicBoolean taskRan = new AtomicBoolean(false);
    executor
        .submit(
            executor.createTask(
                () -> {
                  taskRan.set(true);
                  return null;
                }))
        .get();

    assertTrue(taskRan.get());
  }

  @Test
  public void submitMultipleTaskAllRuns() throws InterruptedException, ExecutionException {
    final int numTask = 5;

    LongAdder adder = new LongAdder();
    List<TaskType> tasks = new ArrayList<>();
    for (int i = 0; i < numTask; i++) {
      tasks.add(
          executor.createTask(
              () -> {
                adder.increment();
                return null;
              }));
    }
    for (Future<?> future : executor.submitAll(tasks)) {
      future.get();
    }

    assertEquals(numTask, adder.intValue());
  }

  @Test
  public void submitRejectsAfterShutdown() {
    expectedException.expect(RejectedExecutionException.class);

    executor.close();
    assertTrue(executor.isShutdown());
    executor.submit(executor.createTask(() -> null));
  }

  // timeout is set to prevent waiting for a blocking call: Ex. future.get().
  @Test(timeout = 1000)
  public void shutdownNowStopsWorkExecutionImmediately()
      throws InterruptedException, ExecutionException {
    Semaphore task1Sem = new Semaphore(0);
    TaskType task1 =
        executor.createTask(
            () -> {
              task1Sem.release();
              return null;
            });

    Semaphore sem = new Semaphore(0);
    TaskType task2 =
        executor.createTask(
            () -> {
              sem.acquire();
              return null;
            });
    TaskType task3 =
        executor.createTask(
            () -> {
              sem.acquire();
              return null;
            });
    TaskType task4 = executor.createTask(() -> null);

    Future<Object> f1 = executor.submit(task1);
    Future<Object> f2 = executor.submit(task2);
    Future<Object> f3 = executor.submit(task3);
    Future<Object> f4 = executor.submit(task4);

    task1Sem.acquire();

    executor.close();
    assertTrue(executor.isShutdown());

    f1.get();
    assertTrue(f1.isDone());
    assertTrue(!f2.isDone() || wasInterrupted(f2));
    assertTrue(!f3.isDone() || wasInterrupted(f3));
    assertFalse(f4.isDone());
  }

  private boolean wasInterrupted(Future<?> future) {
    try {
      future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      return cause != null && cause instanceof InterruptedException;
    } catch (InterruptedException e) {
      return false;
    }
    return false;
  }

  @Test
  public void runsPrereqAndDepTasksWithCorrectScheduling()
      throws ExecutionException, InterruptedException {
    AtomicBoolean task1Done = new AtomicBoolean();
    TaskType task1 =
        executor.createTask(
            () -> {
              task1Done.set(true);
              return null;
            });

    AtomicBoolean task2Done = new AtomicBoolean();
    TaskType task2 =
        executor.createTask(
            () -> {
              task2Done.set(true);
              return null;
            });
    TaskType task3 =
        executor.createThrowingTask(
            () -> {
              assertTrue(task2Done.get());
              return null;
            },
            () -> ImmutableSet.of(task1),
            () -> {
              assertTrue(task1Done.get());
              return ImmutableSet.of(task2);
            });
    executor.submit(task3).get();
  }
}
