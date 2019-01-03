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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareTask.TaskStatus;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultDepsAwareTaskTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void discoversDepsCallsSupplier() throws Exception {
    LongAdder longAdder = new LongAdder();

    DefaultDepsAwareTask<?> task1 =
        DefaultDepsAwareTask.of(
            () -> null,
            () -> {
              longAdder.increment();
              return ImmutableSet.of();
            });
    assertEquals(ImmutableSet.of(), task1.getDependencies());

    assertEquals(1, longAdder.intValue());
    DefaultDepsAwareTask<?> task2 =
        DefaultDepsAwareTask.of(
            () -> null,
            () -> {
              longAdder.increment();
              return ImmutableSet.of(task1);
            });

    assertEquals(ImmutableSet.of(task1), ImmutableSet.copyOf(task2.getDependencies()));
    assertEquals(2, longAdder.intValue());
  }

  @Test
  public void evaluateCompletesFutureNormally() throws ExecutionException, InterruptedException {
    DefaultDepsAwareTask<Integer> task = DefaultDepsAwareTask.of(() -> 1);
    Future<Integer> f = task.getResultFuture();
    Verify.verify(task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED));
    task.call();

    assertTrue(f.isDone());
    assertEquals(1, (int) f.get());
  }

  @Test
  public void evaluateCompletesFutureExceptionally()
      throws ExecutionException, InterruptedException {
    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    DefaultDepsAwareTask<Integer> task =
        DefaultDepsAwareTask.of(
            () -> {
              throw exception;
            });
    Future<Integer> f = task.getResultFuture();
    Verify.verify(task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.STARTED));
    task.call();

    assertTrue(f.isDone());
    f.get();
  }
}
