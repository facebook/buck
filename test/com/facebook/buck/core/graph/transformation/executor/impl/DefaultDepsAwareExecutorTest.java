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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultDepsAwareExecutorTest {

  private ForkJoinPool pool = new ForkJoinPool(2);
  private DefaultDepsAwareExecutor<Object> executor;
  public @Rule ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    executor = DefaultDepsAwareExecutor.from(pool);
  }

  @After
  public void cleanUp() {
    executor.shutdownNow();
  }

  @Test
  public void submittedTaskRuns() throws InterruptedException, ExecutionException {
    AtomicBoolean taskRan = new AtomicBoolean(false);
    executor
        .submit(
            DefaultDepsAwareTask.of(
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
    List<DefaultDepsAwareTask<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < numTask; i++) {
      tasks.add(
          DefaultDepsAwareTask.of(
              () -> {
                adder.increment();
                return null;
              }));
    }
    List<Future<Object>> futures = executor.submitAll(tasks);

    for (Future<Object> future : futures) {
      future.get();
    }

    assertEquals(numTask, adder.intValue());
  }

  @Test
  public void submitRejectsAfterShutdown() {
    expectedException.expect(RejectedExecutionException.class);

    executor.shutdownNow();
    assertTrue(executor.isShutdown());
    executor.submit(DefaultDepsAwareTask.of(() -> null));
  }

  @Test
  public void shutdownNowStopsWorkExecutionImmediately()
      throws InterruptedException, ExecutionException {
    Semaphore task1Sem = new Semaphore(0);
    DefaultDepsAwareTask<Object> task1 =
        DefaultDepsAwareTask.of(
            () -> {
              task1Sem.release();
              return null;
            });

    Semaphore sem = new Semaphore(0);
    DefaultDepsAwareTask<Object> task2 =
        DefaultDepsAwareTask.of(
            () -> {
              sem.acquire();
              return null;
            });
    DefaultDepsAwareTask<Object> task3 =
        DefaultDepsAwareTask.of(
            () -> {
              sem.acquire();
              return null;
            });
    DefaultDepsAwareTask<Object> task4 = DefaultDepsAwareTask.of(() -> null);

    Future<Object> f1 = executor.submit(task1);
    Future<Object> f2 = executor.submit(task2);
    Future<Object> f3 = executor.submit(task3);
    Future<Object> f4 = executor.submit(task4);

    task1Sem.acquire();

    executor.shutdownNow();
    assertTrue(executor.isShutdown());

    f1.get();
    assertTrue(f1.isDone());
    assertFalse(f2.isDone());
    assertFalse(f3.isDone());
    assertFalse(f4.isDone());
  }
}
